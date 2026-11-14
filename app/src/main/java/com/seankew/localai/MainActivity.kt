package com.seankew.localai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 浅色 / 深色两套配色 */
private class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF0F1115.toInt() else 0xFFF5F6F8.toInt()
    val surface = if (dark) 0xFF1A1D23.toInt() else 0xFFFFFFFF.toInt()
    val surfaceAlt = if (dark) 0xFF262A32.toInt() else 0xFFEBEDF1.toInt()
    val stroke = if (dark) 0xFF2B3038.toInt() else 0xFFE2E5EA.toInt()
    val text = if (dark) 0xFFECEEF1.toInt() else 0xFF1B1D22.toInt()
    val textMuted = if (dark) 0xFF8B929D.toInt() else 0xFF767C86.toInt()
    val accent = 0xFF4F6BFF.toInt()
    val onAccent = 0xFFFFFFFF.toInt()
    val ok = 0xFF30C46B.toInt()
    val busy = 0xFFFFB020.toInt()
    val bad = 0xFFFF5A4F.toInt()
}

/** 禁用时自动变淡的胶囊按钮 */
private class PillButton(context: Context) : Button(context) {
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}

/** 圆形图标按钮：禁用变淡，并把启用状态变化通知出去（用来互换"发送/停止"） */
private class FabButton(context: Context) : Button(context) {
    var onEnabledChanged: ((Boolean) -> Unit)? = null
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
        onEnabledChanged?.invoke(enabled)
    }
}

/** 限制最大高度的 ScrollView（模型面板内容较多时不至于吃掉整个屏幕） */
private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}

class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 界面状态与样式 ----
    private lateinit var p: Palette
    private lateinit var rootLayout: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusSubtitle: TextView
    private lateinit var chevronView: TextView
    private lateinit var panelScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var emptyState: View
    private lateinit var emptyHint: TextView
    private var panelExpanded = true
    private var stickToBottom = true

    // ---- 原有成员（名称不变）----
    private lateinit var fileStatusView: TextView
    private lateinit var eventStatusView: TextView
    private lateinit var outputView: TextView          // 现在指向"当前这条模型回答"的文本框
    private lateinit var outputScroll: ScrollView
    private lateinit var promptInput: EditText
    private lateinit var importButton: Button
    private lateinit var initButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var releaseButton: Button

    private var llm: MnnLlm? = null
    private var importing = false

    private val modelDir: File
        get() = File(getExternalFilesDir(null) ?: filesDir, "qwen2.5-1.5b-mnn")

    private val requiredModelFiles = listOf(
        "config.json",
        "llm_config.json",
        "llm.mnn",
        "llm.mnn.weight",
        "tokenizer.txt"
    )

    // These files are optional for this runtime/checklist, but are copied when present.
    private val optionalModelFiles = listOf("llm.mnn.json", "embeddings_bf16.bin")

    companion object {
        private const val REQUEST_MODEL_FOLDER = 3107
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        p = Palette(isDarkMode())
        window.setBackgroundDrawable(ColorDrawable(p.bg))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildScreen()
        refreshModelStatus()
        updateStatus()
    }

    // =====================================================================
    //  界面构建
    // =====================================================================
    private fun buildScreen() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets) }
        }

        // ---------- 1. 顶栏：状态灯 + 标题 + 新对话 ----------
        statusDot = View(this).apply { background = oval(p.bad) }
        val title = TextView(this).apply {
            text = "LocalAI"
            textSize = 18f
            typeface = medium
            setTextColor(p.text)
        }
        chevronView = TextView(this).apply {
            text = "▾"
            textSize = 12f
            setTextColor(p.textMuted)
            setPadding(dp(6), dp(2), 0, 0)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(chevronView)
        }
        statusSubtitle = TextView(this).apply {
            text = "模型未就绪"
            textSize = 12f
            setTextColor(p.textMuted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleRow, wrapWrap())
            addView(statusSubtitle, matchWrap())
        }
        val statusCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { setPanelExpanded(!panelExpanded) }
            addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
            addView(titleColumn, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val newChatButton = TextView(this).apply {
            text = "新对话"
            textSize = 13f
            typeface = medium
            setTextColor(p.text)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(p.surface, 18f, p.stroke)
            isClickable = true
            setOnClickListener { startNewChat() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(14), dp(10))
            addView(statusCluster, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(newChatButton, wrapWrap())
        }
        rootLayout.addView(header, matchWrap())
        rootLayout.addView(View(this).apply { setBackgroundColor(p.stroke) },
            LinearLayout.LayoutParams(MATCH, dp(1)))

        // ---------- 2. 可折叠的模型面板（文件状态 / 导入 / 初始化 / 释放 / 日志）----------
        fileStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(p.text)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        importButton = pill("导入模型", filled = false).apply { setOnClickListener { chooseModelFolder() } }
        initButton = pill("初始化", filled = true).apply { setOnClickListener { initializeModel() } }
        releaseButton = pill("释放", filled = false).apply {
            isEnabled = false
            setOnClickListener { releaseModel() }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(importButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(initButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(releaseButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        eventStatusView = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(p.textMuted)
            setTextIsSelectable(true)
            text = "先导入模型文件，再初始化。"
        }
        val panelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.surface, 16f, p.stroke)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(sectionLabel("模型文件"))
            addView(fileStatusView, matchWrap().apply { topMargin = dp(6) })
            addView(buttonRow, matchWrap().apply { topMargin = dp(14) })
            addView(sectionLabel("运行日志"), matchWrap().apply { topMargin = dp(16) })
            addView(eventStatusView, matchWrap().apply { topMargin = dp(6) })
        }
        panelScroll = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.5f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(14), dp(10), dp(14), dp(4))
            addView(panelCard, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        rootLayout.addView(panelScroll, matchWrap())

        // ---------- 3. 中间：对话流 + 空状态欢迎页 ----------
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        outputScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chatContainer, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        // 用户向上翻阅时停止自动跟随，回到底部附近再恢复
        outputScroll.viewTreeObserver.addOnScrollChangedListener { stickToBottom = isNearBottom() }

        emptyHint = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(p.textMuted)
            setPadding(dp(32), dp(6), dp(32), 0)
        }
        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = "✦"
                textSize = 40f
                gravity = Gravity.CENTER
                setTextColor(p.accent)
            }, wrapWrap())
            addView(TextView(this@MainActivity).apply {
                text = "有什么可以帮你？"
                textSize = 22f
                typeface = medium
                gravity = Gravity.CENTER
                setTextColor(p.text)
                setPadding(0, dp(8), 0, 0)
            }, wrapWrap())
            addView(emptyHint, matchWrap())
        }
        val chatFrame = FrameLayout(this).apply {
            addView(outputScroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyState, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        rootLayout.addView(chatFrame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---------- 4. 底栏：圆角输入框 + 发送/停止 ----------
        promptInput = EditText(this).apply {
            hint = "给 LocalAI 发消息"
            setHintTextColor(p.textMuted)
            setTextColor(p.text)
            textSize = 16f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 5
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), dp(8), dp(9))
        }
        val send = fab("↑", p.accent, p.onAccent, "发送").apply { setOnClickListener { sendPrompt() } }
        val stop = fab("■", p.text, p.bg, "停止").apply {
            setOnClickListener {
                llm?.stop()
                appendEvent("已请求停止生成。")
            }
        }
        sendButton = send
        stopButton = stop
        // 停止按钮启用 = 正在生成：此时显示"停止"，隐藏"发送"；反之亦然
        stop.onEnabledChanged = { generating ->
            stop.visibility = if (generating) View.VISIBLE else View.GONE
            send.visibility = if (generating) View.GONE else View.VISIBLE
        }
        send.isEnabled = false
        stop.isEnabled = false

        val actionSlot = FrameLayout(this).apply {
            addView(send, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(stop, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val inputPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = rounded(p.surface, 28f, p.stroke)
            setPadding(dp(18), dp(4), dp(6), dp(5))
            addView(promptInput, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(actionSlot, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(inputPill, matchWrap())
        }
        rootLayout.addView(composer, matchWrap())

        setContentView(rootLayout)
        applySystemBars()
        setPanelExpanded(true)      // 首次进入：先展开，引导导入并初始化模型
        updateEmptyState()
    }

    // =====================================================================
    //  界面辅助：状态灯 / 面板 / 气泡 / 滚动
    // =====================================================================
    private fun updateStatus() {
        val state = llm?.currentState
        val (color, label) = when {
            importing -> p.busy to "正在导入模型…"
            state == MnnLlm.State.INITIALIZING -> p.busy to "正在初始化…"
            state == MnnLlm.State.RELEASING -> p.busy to "正在释放…"
            state == MnnLlm.State.GENERATING -> p.ok to "生成中…"
            state == MnnLlm.State.READY -> p.ok to "模型就绪 · 离线运行"
            else -> p.bad to "模型未就绪 · 点击标题展开"
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusSubtitle.text = label
        emptyHint.text = if (state == MnnLlm.State.READY || state == MnnLlm.State.GENERATING)
            "输入问题，开始对话" else "点击上方「LocalAI ▾」，导入并初始化模型"
    }

    private fun setPanelExpanded(expanded: Boolean) {
        panelExpanded = expanded
        TransitionManager.beginDelayedTransition(rootLayout)
        panelScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        chevronView.text = if (expanded) "▴" else "▾"
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (chatContainer.childCount == 0) View.VISIBLE else View.GONE
    }

    private fun addUserBubble(text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(p.onAccent)
            setLineSpacing(0f, 1.2f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = bubbleDrawable(p.accent)
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            setTextIsSelectable(true)
        }
        chatContainer.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.END
            topMargin = dp(16)
        })
    }

    /** 模型回答：不套气泡，直接铺正文，行距舒适；首个 token 到来前显示"思考中…" */
    private fun addAssistantBubble(): TextView {
        val view = TextView(this).apply {
            textSize = 16f
            setTextColor(p.text)
            setLineSpacing(dp(3).toFloat(), 1.12f)
            hint = "思考中…"
            setHintTextColor(p.textMuted)
            setPadding(dp(2), 0, dp(2), 0)
        }
        chatContainer.addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        return view
    }

    private fun isNearBottom(): Boolean {
        val content = outputScroll.getChildAt(0) ?: return true
        return content.bottom - (outputScroll.height + outputScroll.scrollY) < dp(96)
    }

    // 不用 fullScroll()：它可能把焦点从输入框抢走，导致键盘收起
    private fun scrollToBottom() {
        outputScroll.post { outputScroll.scrollTo(0, chatContainer.bottom) }
    }

    private fun warn(message: String) {
        appendEvent(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // =====================================================================
    //  绘制工具
    // =====================================================================
    private val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun isDarkMode() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(radiusDp)
            setColor(color)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun bubbleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        val big = dpf(20f)
        val small = dpf(6f)
        cornerRadii = floatArrayOf(big, big, big, big, small, small, big, big)   // 右下角收小，形成"尾巴"
        setColor(color)
    }

    private fun sectionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = medium
        letterSpacing = 0.04f
        setTextColor(p.textMuted)
    }

    private fun pill(label: String, filled: Boolean): Button = PillButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = medium
        setTextColor(if (filled) p.onAccent else p.text)
        background = rounded(if (filled) p.accent else p.surfaceAlt, 14f)
        stateListAnimator = null
        minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun fab(symbol: String, bg: Int, fg: Int, description: String): FabButton = FabButton(this).apply {
        text = symbol
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        isAllCaps = false
        includeFontPadding = false
        gravity = Gravity.CENTER
        background = oval(bg)
        stateListAnimator = null
        minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        contentDescription = description
    }

    // 系统栏 / 键盘：API 30+ 走沉浸式并手动处理 insets；更低版本用系统默认的 adjustResize
    @Suppress("DEPRECATION")
    private fun applyInsets(v: View, insets: WindowInsets): WindowInsets {
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            top = bars.top
            bottom = bars.bottom
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
        }
        v.setPadding(0, top, 0, bottom)
        return insets
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else light, light)
        } else if (Build.VERSION.SDK_INT >= 23 && !p.dark) {
            var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    // =====================================================================
    //  业务逻辑（函数签名与行为保持不变；仅在界面相关处补充了状态刷新）
    // =====================================================================
    private fun refreshModelStatus() {
        val lines = mutableListOf(
            "模型目录：",
            modelDir.absolutePath,
            "",
            "必需文件："
        )
        requiredModelFiles.forEach { name ->
            val modelFile = File(modelDir, name)
            lines += if (modelFile.isFile && modelFile.length() > 0L) {
                "✓ $name（${modelFile.length()} bytes）"
            } else {
                "✗ $name：缺失"
            }
        }
        fileStatusView.text = lines.joinToString("\n")
        if (!modelDir.exists()) modelDir.mkdirs()
    }

    private fun chooseModelFolder() {
        if (importing) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_MODEL_FOLDER)
    }

    @Deprecated("Uses the platform document-tree picker for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL_FOLDER || resultCode != RESULT_OK) return
        val treeUri = data?.data ?: return
        val flags = (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) or
            (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, flags)
        }.onFailure {
            appendEvent("无法保留文件夹读取权限：${it.message}")
        }
        importModelFolder(treeUri)
    }

    private fun importModelFolder(treeUri: Uri) {
        importing = true
        importButton.isEnabled = false
        initButton.isEnabled = false
        sendButton.isEnabled = false
        appendEvent("正在导入模型文件。llm.mnn.weight 较大，请保持 App 在前台并等待复制完成……")

        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { copyModelFiles(treeUri) }
            }
            importing = false
            importButton.isEnabled = true
            initButton.isEnabled = true
            result.fold(
                onSuccess = { copied ->
                    refreshModelStatus()
                    appendEvent("导入完成：${copied.joinToString(", ").ifEmpty { "没有找到可导入的模型文件" }}")
                    val missing = requiredModelFiles.filter { !File(modelDir, it).isFile || File(modelDir, it).length() == 0L }
                    if (missing.isEmpty()) {
                        appendEvent("必需文件已齐全。现在可以点击“初始化”。")
                    } else {
                        appendEvent("仍缺少：${missing.joinToString(", ")}。请确认选择的是直接包含 config.json 的文件夹。")
                    }
                },
                onFailure = { error ->
                    refreshModelStatus()
                    appendEvent("导入失败：${error.message ?: error.javaClass.simpleName}")
                }
            )
        }
    }

    private fun copyModelFiles(treeUri: Uri): List<String> {
        val sourceFolder = DocumentFile.fromTreeUri(this, treeUri)
            ?: throw IllegalStateException("无法读取所选文件夹")
        if (!sourceFolder.isDirectory) throw IllegalArgumentException("请选择解压后的模型文件夹，而不是单个文件")

        modelDir.mkdirs()
        val copied = mutableListOf<String>()
        (requiredModelFiles + optionalModelFiles).forEach { name ->
            val source = sourceFolder.findFile(name) ?: return@forEach
            if (!source.isFile) return@forEach
            val destination = File(modelDir, name)
            val temporary = File(modelDir, "$name.importing")
            contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("无法读取文件：$name")

            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                throw IllegalStateException("无法替换旧文件：$name")
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw IllegalStateException("无法保存文件：$name")
            }
            copied += name
        }
        return copied
    }

    private fun initializeModel() {
        if (importing) {
            appendEvent("模型文件正在导入，请稍候。")
            return
        }
        if (llm != null) {
            appendEvent("模型对象已存在；如需重新初始化，请先释放模型。")
            return
        }
        val missing = requiredModelFiles.filter { !File(modelDir, it).isFile || File(modelDir, it).length() == 0L }
        if (missing.isNotEmpty()) {
            appendEvent("无法初始化，缺少文件：${missing.joinToString(", ")}")
            refreshModelStatus()
            return
        }

        initButton.isEnabled = false
        importButton.isEnabled = false
        sendButton.isEnabled = false
        appendEvent("正在加载 MNN 原生库并初始化模型……")
        val engine = MnnLlm(applicationContext)
        llm = engine
        uiScope.launch {
            val result = engine.init(
                modelDir = modelDir,
                threads = 4,
                backend = "cpu",
                precision = "low",
                maxNewTokens = LlmDefaults.MAX_NEW_TOKENS,
                maxAllTokens = LlmDefaults.MAX_ALL_TOKENS,
                systemPrompt = AdvisorPrompt.DEFAULT
            )
            result.fold(
                onSuccess = {
                    appendEvent("✓ 模型初始化成功。现在可以输入问题并点击发送。")
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                    releaseButton.isEnabled = true
                    initButton.isEnabled = false
                    importButton.isEnabled = false
                    setPanelExpanded(false)          // 就绪后收起面板，让出对话空间
                },
                onFailure = { error ->
                    appendEvent("初始化失败：${error.message ?: error.javaClass.simpleName}")
                    llm = null
                    engine.release()
                    initButton.isEnabled = true
                    importButton.isEnabled = true
                    refreshModelStatus()
                    setPanelExpanded(true)           // 失败时展开面板，方便查看日志
                }
            )
        }
        updateStatus()   // launch 内的 init 已同步进入 INITIALIZING，这里立即刷新状态灯
    }

    private fun sendPrompt() {
        val engine = llm
        if (engine == null || !engine.isReady) {
            warn("模型尚未成功初始化，因此发送暂不可用。请先初始化模型。")
            sendButton.isEnabled = false
            setPanelExpanded(true)
            return
        }
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty()) {
            warn("请先输入问题。")
            return
        }

        sendButton.isEnabled = false
        stopButton.isEnabled = true
        promptInput.setText("")
        addUserBubble(prompt)
        val bubble = addAssistantBubble()
        outputView = bubble
        bubble.text = ""
        updateEmptyState()
        stickToBottom = true
        scrollToBottom()
        appendEvent("开始生成……")
        uiScope.launch {
            try {
                engine.chat(prompt).collect { piece ->
                    bubble.append(piece)
                    if (stickToBottom) scrollToBottom()
                }
                appendEvent("生成结束：${engine.lastFinishReason ?: "未知原因"}")
                if (engine.lastFinishReason == FinishReason.MAX_TOKENS) bubble.append("\n\n…（已达到长度上限）")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                warn("生成失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                sendButton.isEnabled = engine.isReady
                stopButton.isEnabled = false
                if (bubble.text.isEmpty()) bubble.hint = "（无输出）"
                bubble.setTextIsSelectable(true)     // 流式输出期间不开选择，结束后再允许复制
                updateStatus()
            }
        }
    }

    private fun releaseModel() {
        val engine = llm ?: return
        sendButton.isEnabled = false
        stopButton.isEnabled = false
        releaseButton.isEnabled = false
        appendEvent("正在释放模型资源……")
        engine.release()
        updateStatus()
        uiScope.launch {
            runCatching { engine.awaitReleased() }
            if (llm === engine) llm = null
            chatContainer.removeAllViews()           // 模型上下文已销毁，界面上的旧对话一并清掉
            updateEmptyState()
            appendEvent("模型资源已释放。")
            initButton.isEnabled = true
            importButton.isEnabled = true
            refreshModelStatus()
            setPanelExpanded(true)
        }
    }

    /** 新增：清空界面对话与模型端的对话历史（MnnLlm.reset） */
    private fun startNewChat() {
        if (stopButton.isEnabled) {
            Toast.makeText(this, "请先停止当前生成", Toast.LENGTH_SHORT).show()
            return
        }
        chatContainer.removeAllViews()
        updateEmptyState()
        val engine = llm ?: return
        if (!engine.isReady) return
        uiScope.launch {
            try {
                engine.reset()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appendEvent("清空上下文失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun appendEvent(message: String) {
        // 只保留最近 16 行，避免日志无限增长；状态灯随事件同步刷新
        val lines = (eventStatusView.text.toString() + "\n" + message).lines().takeLast(16)
        eventStatusView.text = lines.joinToString("\n")
        updateStatus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun wrapWrap() = LinearLayout.LayoutParams(WRAP, WRAP)

    override fun onDestroy() {
        llm?.release()
        llm = null
        uiScope.cancel()
        super.onDestroy()
    }
}