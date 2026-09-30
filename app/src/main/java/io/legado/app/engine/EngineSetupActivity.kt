package io.legado.app.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.data.appDb
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.service.WebService
import io.legado.app.ui.book.source.manage.BookSourceActivity

/**
 * 引擎面板(纯本地): 运行状态 / 局域网地址 / THP 端点 / 书源导入指引
 *
 * 本引擎无需账号、无需联网注册: 装上即可被同一局域网内的阅读前端发现并调用。
 * 用户唯一需要做的, 是导入书源。
 */
class EngineSetupActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val dp get() = resources.displayMetrics.density

    private fun Int.px(): Int = (this * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = EngineBeacon.NAME

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.px(), 20.px(), 20.px(), 20.px())
        }

        root.addView(sectionTitle("运行状态"))
        tvStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(if (isNight()) Color.parseColor("#DDDDDD") else Color.parseColor("#333333"))
            setLineSpacing(6.px().toFloat(), 1f)
        }
        root.addView(tvStatus)

        root.addView(sectionTitle("快捷操作"))

        root.addView(actionButton("复制引擎地址") {
            val url = EngineBeacon.lanUrl()
            if (url.isEmpty()) {
                toast("引擎尚未就绪, 请稍候")
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("engine_url", url))
                toast("已复制: $url")
            }
        })

        root.addView(actionButton("打开书源管理") {
            runCatching { startActivity(Intent(this, BookSourceActivity::class.java)) }
        })

        root.addView(actionButton("重启引擎服务") {
            WebService.startForeground(this)
            ThpServer.ensureStarted()
            tvStatus.postDelayed({ refreshStatus() }, 500)
            toast("引擎服务已重新拉起")
        })

        root.addView(sectionTitle("内置源激活"))

        root.addView(actionButton("激活 / 重新解锁内置源") {
            startActivity(EngineActivationActivity.intentFor(this))
        })

        root.addView(actionButton("修改激活密码") { showChangePasswordDialog() })

        root.addView(hintText(
            "· 未激活时内置源不会入库, 局域网内的阅读前端也搜不到任何内容。\n" +
                "· 出厂默认密码 ${EngineProfile.DEFAULT_PASSWORD}；修改后请自行记牢, " +
                "忘密码只能卸载重装。\n" +
                "· 已激活的机器可以直接在下面的书源管理里增删源; " +
                "本产物只认本类型的源(" +
                EngineProfile.sourceTypes.sorted().joinToString("/") { typeName(it) } +
                "), 其他类型会被过滤掉。"
        ))

        root.addView(sectionTitle("使用说明"))
        root.addView(hintText(
            "1. 打开「书源管理」导入书源(本地文件 / 网络导入 / 二维码均可)。\n" +
                "2. 书源导入后无需其他设置, 引擎自动生效。\n" +
                "3. 同一局域网内的阅读前端会自动发现本机, 也可手动填入上面的引擎地址。\n" +
                "4. 前端发起搜索、目录、正文请求时, 全部由本机引擎解析书源并返回。\n\n" +
                "本引擎在后台常驻运行, 不支持也不进行任何云端注册、账号登录与数据上报。\n" +
                "书源数量越多, 可搜索到的内容越全 —— 阅读体验取决于你所导入的书源。"
        ))

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    /**
     * 可用书源数。-1 = 还没统计出来。
     * ★不能在 refreshStatus() 里直接查 Room：那个函数每 3 秒跑一次，且跑在主线程，
     *   Room 默认禁止主线程查询会直接抛 IllegalStateException 崩掉面板。
     *   改为 onResume 时在 IO 上查一次，面板只负责格式化显示。
     */
    private var cachedSourceCount: Int = -1

    override fun onResume() {
        super.onResume()
        Coroutine.async {
            cachedSourceCount = runCatching { appDb.bookSourceDao.allEnabled.size }.getOrDefault(-1)
        }
    }

    private fun refreshStatus() {
        val host = EngineBeacon.lanHost()
        tvStatus.text = buildString {
            appendLine("引擎服务:  ${if (WebService.isRun) "运行中" else "启动中…"}")
            appendLine("产物:  ${EngineProfile.displayName}${if (EngineProfile.isAllInOne) "（四合一）" else ""}")
            appendLine("支持模块:  ${EngineProfile.modules.joinToString(" / ") { moduleName(it) }}")
            appendLine("激活状态:  ${if (EngineProfile.activated) "已激活" else "未激活 · 内置源不可用"}（${EngineProfile.passwordSource}）")
            appendLine("可用书源:  ${if (cachedSourceCount < 0) "统计中…" else "$cachedSourceCount 条"}")
            appendLine("局域网地址:  ${EngineBeacon.lanUrl().ifEmpty { "获取中…" }}")
            appendLine("发现广播:  UDP ${EngineBeacon.BEACON_PORT}   THP/1 HELLO   caps=${EngineBeacon.CAPS.ifEmpty { "(未激活, 不广播能力)" }}")
            appendLine("协议版本:  ${ThpServer.VERSION}")
            if (host.isEmpty()) appendLine("提示:  未检测到局域网地址, 请确认已连接 Wi-Fi")
        }
        tvStatus.postDelayed({ if (!isDestroyed) refreshStatus() }, 3000)
    }

    private fun moduleName(m: String): String = when (m) {
        "novel" -> "小说"
        "comic" -> "漫画"
        "music" -> "音乐"
        "video" -> "影视"
        else -> m
    }

    private fun typeName(t: Int): String = when (t) {
        0 -> "文本"
        1 -> "音频"
        2 -> "图片"
        3 -> "文件"
        4 -> "视频"
        else -> "类型$t"
    }

    /** 改激活密码：旧密码 + 新密码 + 确认，全部本地校验，不联网。 */
    private fun showChangePasswordDialog() {
        val pad = 20.px()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun field(hint: String) = android.widget.EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val etOld = field("当前密码")
        val etNew = field("新密码（4–64 位）")
        val etRepeat = field("再输一次新密码")
        box.addView(etOld)
        box.addView(etNew)
        box.addView(etRepeat)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("修改激活密码")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {
                            val old = etOld.text.toString()
                            val n1 = etNew.text.toString()
                            val n2 = etRepeat.text.toString()
                            when {
                                !EngineProfile.verifyPassword(old) -> toast("当前密码不正确")
                                n1.length < 4 || n1.length > 64 -> toast("新密码需 4–64 位")
                                n1 != n2 -> toast("两次输入的新密码不一致")
                                n1 == EngineProfile.DEFAULT_PASSWORD -> toast("新密码不能等于出厂默认密码")
                                EngineProfile.setPassword(n1) -> {
                                    toast("密码已更新")
                                    dismiss()
                                }
                                else -> toast("保存失败，请重试")
                            }
                        }
                }
            }
            .show()
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (isNight()) Color.WHITE else Color.BLACK)
        setPadding(0, 18.px(), 0, 8.px())
    }

    private fun hintText(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(if (isNight()) Color.parseColor("#AAAAAA") else Color.parseColor("#666666"))
        setLineSpacing(6.px().toFloat(), 1f)
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun isNight(): Boolean =
        resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
