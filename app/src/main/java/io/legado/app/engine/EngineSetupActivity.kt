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

    private fun refreshStatus() {
        val host = EngineBeacon.lanHost()
        tvStatus.text = buildString {
            appendLine("引擎服务:  ${if (WebService.isRun) "运行中" else "启动中…"}")
            appendLine("局域网地址:  ${EngineBeacon.lanUrl().ifEmpty { "获取中…" }}")
            appendLine("发现广播:  UDP ${EngineBeacon.BEACON_PORT}   THP/1 HELLO")
            appendLine("支持内容:  小说 / 漫画 / 听书 / 视频")
            appendLine("协议版本:  ${ThpServer.VERSION}")
            if (host.isEmpty()) appendLine("提示:  未检测到局域网地址, 请确认已连接 Wi-Fi")
        }
        tvStatus.postDelayed({ if (!isDestroyed) refreshStatus() }, 3000)
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
