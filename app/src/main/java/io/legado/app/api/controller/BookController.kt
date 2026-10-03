package io.legado.app.api.controller

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDav
import io.legado.app.help.CacheManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
// ★2026-10-03：正文为空要与"其它异常"区分开，前端据此给"换源"出口（见 getBookContent）。
import io.legado.app.exception.ContentEmptyException
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

private val windowsDrivePrefix = Regex("^[A-Za-z]:")

internal fun requireSafeUploadedBookFileName(fileName: String): String {
    val isUnsafe = fileName.isBlank() ||
            fileName == "." ||
            fileName == ".." ||
            fileName.indexOfAny(charArrayOf('/', '\\')) >= 0 ||
            windowsDrivePrefix.containsMatchIn(fileName) ||
            fileName.any { Character.isISOControl(it) }
    require(!isUnsafe) { "Invalid uploaded book file name" }
    return fileName
}

object BookController {

    private data class ImageContext(
        val bookUrl: String,
        val book: Book,
        val bookSource: BookSource?,
    )

    @Volatile
    private var cachedImageContext: ImageContext? = null
    private val defaultCoverCache by lazy { WeakHashMap<Drawable, Bitmap>() }

    /**
     * 书架所有书籍
     */
    val bookshelf: ReturnData
        get() {
            val books = appDb.bookDao.all
            val returnData = ReturnData()
            return if (books.isEmpty()) {
                returnData.setErrorMsg("还没有添加小说")
            } else {
                val data = when (AppConfig.bookshelfSort) {
                    1 -> books.sortedByDescending { it.latestChapterTime }
                    2 -> books.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> books.sortedBy { it.order }
                    else -> books.sortedByDescending { it.durChapterTime }
                }
                returnData.setData(data)
            }
        }

    /**
     * 获取封面
     */
    fun getCover(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val coverPath = parameters["path"]?.firstOrNull()
        val ftBitmap = ImageLoader.loadBitmap(appCtx, coverPath)
            .override(84, 112)
            .centerCrop()
            .submit()
        return try {
            returnData.setData(ftBitmap.get(3, TimeUnit.SECONDS))
        } catch (e: Exception) {
            try {
                val defaultBitmap = defaultCoverCache.getOrPut(BookCover.defaultDrawable) {
                    Glide.with(appCtx)
                        .asBitmap()
                        .load(BookCover.defaultDrawable.toBitmap())
                        .override(84, 112)
                        .centerCrop()
                        .submit()
                        .get()
                }
                returnData.setData(defaultBitmap)
            } catch (e: Exception) {
                returnData.setErrorMsg(e.localizedMessage ?: "getCover error")
            }
        }
    }

    /**
     * 获取正文图片
     */
    fun getImg(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val bookUrl = parameters["url"]?.firstOrNull()
        if (bookUrl.isNullOrBlank()) {
            return returnData.setErrorMsg("bookUrl为空")
        }
        val src = parameters["path"]?.firstOrNull()
            ?: return returnData.setErrorMsg("图片链接为空")
        val width = parameters["width"]?.firstOrNull()?.toInt() ?: 640
        val cachedContext = cachedImageContext
        val imageContext = cachedContext?.takeIf { it.bookUrl == bookUrl }
            ?: appDb.bookDao.getBook(bookUrl)?.let { book ->
                val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                ImageContext(bookUrl, book, bookSource).also {
                    cachedImageContext = it
                }
            } ?: return returnData.setErrorMsg("bookUrl不对")
        val bitmap = runBlocking {
            ImageProvider.cacheImage(imageContext.book, src, imageContext.bookSource)
            ImageProvider.getImage(imageContext.book, src, width)
        }
        return returnData.setData(bitmap)
    }

    /**
     * 更新目录
     */
    fun refreshToc(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        try {
            val bookUrl = parameters["url"]?.firstOrNull()
            if (bookUrl.isNullOrEmpty()) {
                return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
            }
            val book = appDb.bookDao.getBook(bookUrl)
                ?: return returnData.setErrorMsg("未在数据库找到对应书籍，请先添加")
            if (book.isLocal) {
                val toc = LocalBook.getChapterList(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
                book.update()
                return returnData.setData(toc)
            } else {
                val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                    ?: return returnData.setErrorMsg("未找到对应书源,请换源")
                val toc = runBlocking {
                    if (book.tocUrl.isBlank()) {
                        WebBook.getBookInfoAwait(bookSource, book)
                    }
                    WebBook.getChapterListAwait(bookSource, book).getOrThrow()
                }
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
                book.update()
                return returnData.setData(toc)
            }
        } catch (e: Exception) {
            return returnData.setErrorMsg(e.localizedMessage ?: "refresh toc error")
        }
    }

    /**
     * 获取目录
     */
    fun getChapterList(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        val chapterList = appDb.bookChapterDao.getChapterList(bookUrl)
        if (chapterList.isEmpty()) {
            return refreshToc(parameters)
        }
        return returnData.setData(chapterList)
    }

    /**
     * 获取正文
     */
    fun getBookContent(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        val index = parameters["index"]?.firstOrNull()?.toInt()
        val returnData = ReturnData()
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        if (index == null) {
            return returnData.setErrorMsg("参数index不能为空, 请指定目录序号")
        }
        val book = appDb.bookDao.getBook(bookUrl)
        // ★2026-10-03 不再「先空等 30 秒、再发现书压根不在库里」。
        //   旧顺序是：先跑 chapter 的 while(最多 30 次 ×1s)，**之后**才检查 book == null。
        //   而 book 为 null 时 chapter 永远不会出现 —— 于是这条路径**必然空等满 30 秒**
        //   才返回一句「未找到」。这正是「点开书卡住半分钟、然后说获取正文失败」的由来。
        //   现在书不在库就立刻返回，并把原因说清楚（THP 直连常在未登记时直接取正文）。
        if (book == null) {
            return returnData.setErrorMsg(
                "[NEED_REGISTRATION] 这本书还没登记到引擎（THP 直连不会自动加书架），请重新搜索后再打开"
            )
        }
        var chapter = appDb.bookChapterDao.getChapter(bookUrl, index)
        if (chapter == null) {
            // 目录尚未建立（常见于"先取正文、还没取过目录"）→ 补建一次再取，而不是干等。
            runCatching { refreshToc(parameters) }
            chapter = appDb.bookChapterDao.getChapter(bookUrl, index)
        }
        if (chapter == null) {
            return returnData.setErrorMsg("[NEED_REGISTRATION] 目录里没有这一章，请重新搜索后再打开")
        }
        var content: String? = BookHelp.getContent(book, chapter)
        if (content != null) {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            content = runBlocking {
                contentProcessor.getContent(book, chapter, content, includeTitle = false)
                    .toString()
            }
            return returnData.setData(content)
        }
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
            ?: return returnData.setErrorMsg("未找到书源")
        try {
            content = runBlocking {
                WebBook.getContentAwait(bookSource, book, chapter).let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    contentProcessor.getContent(book, chapter, it, includeTitle = false)
                        .toString()
                }
            }
            returnData.setData(content)
        } catch (e: Exception) {
            // ★2026-10-03 把「源抓不到正文」单独标出来，别再一律吐栈。
            //   ContentEmptyException = 站点返回了页面、但正文规则解析不出内容
            //   （源失效 / 站点改版 / 需要登录）。
            //   实测（模拟器 10 本样本，2026-10-03）：目录全部正常，正文 5/10 为空，
            //   且**失败的那 5 本全来自同一个源**（扎堆小说），可读的 5 本全来自另一个源
            //   （阅友小说）；用书名重搜后同名书在 7 个源里有 4 个能读出正文。
            //   ⇒ 这不是"链路坏了"，是**部分源失效**，而前端需要能识别它才能给"换源"出口。
            if (e is ContentEmptyException) {
                returnData.setErrorMsg(
                    "[SOURCE_EMPTY] 这个来源没能返回正文内容（站点可能改版了，或需要登录）"
                )
            } else {
                returnData.setErrorMsg(e.stackTraceStr)
            }
        }
        return returnData
    }

    /**
     * 保存书籍
     */
    suspend fun saveBook(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<Book>(postData).getOrNull()?.let { book ->
            AppWebDav.uploadBookProgress(book)
            book.save()
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 删除书籍
     */
    fun deleteBook(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<Book>(postData).getOrNull()?.let { book ->
            book.delete()
            return returnData.setData("")
        }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 保存进度
     */
    suspend fun saveBookProgress(postData: String?): ReturnData {
        val returnData = ReturnData()
        GSON.fromJsonObject<BookProgress>(postData)
            .onFailure { it.printOnDebug() }
            .getOrNull()?.let { bookProgress ->
                appDb.bookDao.getBook(bookProgress.name, bookProgress.author)?.let { book ->
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    AppWebDav.uploadBookProgress(bookProgress) {
                        book.syncTime = System.currentTimeMillis()
                    }
                    book.update()
                    ReadBook.book?.let {
                        if (it.name == bookProgress.name &&
                            it.author == bookProgress.author
                        ) {
                            ReadBook.webBookProgress = bookProgress
                        }
                    }
                    return returnData.setData("")
                }
            }
        return returnData.setErrorMsg("格式不对")
    }

    /**
     * 添加本地书籍
     */
    fun addLocalBook(
        parameters: Map<String, List<String>>,
        files: Map<String, String>
    ): ReturnData {
        val returnData = ReturnData()
        val rawFileName = parameters["fileName"]?.firstOrNull()
            ?: return returnData.setErrorMsg("fileName 不能为空")
        val fileName = kotlin.runCatching {
            requireSafeUploadedBookFileName(rawFileName)
        }.getOrElse {
            return returnData.setErrorMsg("fileName 格式不正确")
        }
        val fileData = files["fileData"]
            ?: return returnData.setErrorMsg("fileData 不能为空")
        kotlin.runCatching {
            val uri = LocalBook.saveBookFile(File(fileData).inputStream(), fileName)
            LocalBook.importFile(uri)
        }.onFailure {
            return when (it) {
                is SecurityException -> returnData.setErrorMsg("需重新设置书籍保存位置!")
                else -> returnData.setErrorMsg("保存书籍错误\n${it.localizedMessage}")
            }
        }
        return returnData.setData(true)
    }

    /**
     * 保存web阅读界面配置
     */
    fun saveWebReadConfig(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData?.let {
            CacheManager.put("webReadConfig", postData)
        } ?: CacheManager.delete("webReadConfig")
        return returnData.setData("")
    }

    /**
     * 获取web阅读界面配置
     */
    fun getWebReadConfig(): ReturnData {
        val returnData = ReturnData()
        val data = CacheManager.get("webReadConfig")
            ?: return returnData.setErrorMsg("没有配置")
        return returnData.setData(data)
    }

}
