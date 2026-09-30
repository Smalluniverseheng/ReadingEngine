package io.legado.app.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.help.DefaultData
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.startActivity

/**
 * 首次激活页（五产物通用）。
 *
 * 出厂源是私货：装完第一次打开必须先输对密码，源才会入库、THP 才会对外服务。
 * 未激活时引擎的一切数据端点都返回 403 ACTIVATION_REQUIRED（见 ThpServer.activationBlock）。
 *
 * 默认密码见 [EngineProfile.DEFAULT_PASSWORD]（用户要求「先默认一到六」= 123456）。
 * 每次冷启动都会检查一次 —— 反激活后重启就会回到这里，而不是"设一次就永久绕过"。
 */
class EngineActivationActivity : AppCompatActivity() {

    private lateinit var etPassword: EditText
    private lateinit var tvMsg: TextView

    private val dp get() = resources.displayMetrics.density
    private fun Int.px(): Int = (this * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "${EngineProfile.displayName} · 激活"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.px(), 28.px(), 24.px(), 24.px())
        }

        root.addView(TextView(this).apply {
            text = EngineProfile.displayName
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg())
        })
        root.addView(TextView(this).apply {
            text = "本引擎已内置 ${EngineProfile.modules.joinToString(" / ")} 类内容源。\n" +
                "首次使用请输入激活密码解锁 —— 未解锁时这些源不会入库，" +
                "局域网内的阅读前端也无法从本机搜索到任何内容。"
            textSize = 13f
            setTextColor(muted())
            setLineSpacing(7.px().toFloat(), 1f)
            setPadding(0, 12.px(), 0, 20.px())
        })

        etPassword = EditText(this).apply {
            hint = "请输入激活密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 16f
            setSingleLine()
        }
        root.addView(etPassword)

        tvMsg = TextView(this).apply {
            textSize = 13f
            setLineSpacing(5.px().toFloat(), 1f)
            setPadding(0, 12.px(), 0, 4.px())
            setTextColor(muted())
        }
        root.addView(tvMsg)

        root.addView(actionButton("激活并导入内置源") { doActivate() })
        root.addView(actionButton("稍后再说（退出）") { finishAffinity() })

        root.addView(TextView(this).apply {
            text = "默认密码：${EngineProfile.DEFAULT_PASSWORD}\n" +
                "激活后可在「引擎面板 → 修改激活密码」里改。\n" +
                "忘了密码：卸载重装（已导入的书源会一并清空）。"
            textSize = 12f
            setTextColor(muted())
            setLineSpacing(6.px().toFloat(), 1f)
            setPadding(0, 22.px(), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        tvMsg.text = "当前状态：未激活（密码来源：${EngineProfile.passwordSource}）"
    }

    private fun doActivate() {
        val input = etPassword.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            warn("请先输入密码")
            return
        }
        if (!EngineProfile.verifyPassword(input)) {
            warn("密码不正确")
            etPassword.setText("")
            return
        }
        EngineProfile.activate()
        tvMsg.text = "激活成功，正在导入内置源…"
        tvMsg.setTextColor(ok())
        // 激活后立刻导源，不必等下次冷启动；Room 不能在主线程跑，故丢到 IO。
        // ★注意 Coroutine.async 的 lambda 接收者是 CoroutineScope，此处的 this 已被遮蔽，
        //   Toast/startActivity 必须显式写 this@EngineActivationActivity。
        val activity = this
        Coroutine.async {
            val added = runCatching { DefaultData.importDefaultBookSourcesOnActivate() }.getOrDefault(0)
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "已激活，新导入 $added 条内置源",
                    Toast.LENGTH_LONG
                ).show()
                activity.startActivity<MainActivity>()
                activity.finish()
            }
        }
    }

    private fun warn(msg: String) {
        tvMsg.text = msg
        tvMsg.setTextColor(Color.parseColor("#E53935"))
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 10.px() }
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun fg(): Int = if (isNight()) Color.WHITE else Color.BLACK

    private fun muted(): Int =
        if (isNight()) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")

    private fun ok(): Int = Color.parseColor("#43A047")

    private fun isNight(): Boolean =
        resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    companion object {
        fun intentFor(context: Context) = android.content.Intent(context, EngineActivationActivity::class.java)
    }
}
