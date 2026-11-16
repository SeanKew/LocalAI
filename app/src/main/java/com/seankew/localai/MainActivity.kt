package com.seankew.localai

import android.app.AlertDialog
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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.seankew.localai.memory.MemoryRecord
import kotlinx.coroutines.launch

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

/** 选择文件夹：在平台的 OpenDocumentTree 基础上申请可持久化的读取授权（与原实现一致）。 */
private class PersistableOpenDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

/**
 * 纯 UI 观察者：不持有 MnnLlm，也不管理任何模型生命周期。
 * 所有状态来自 [ChatViewModel]；Activity 因旋转 / 深色模式切换被重建时，
 * 只需重新构建视图并重新订阅，模型与对话都原样保留。
 */
class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

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
    private var lastPhase: ModelPhase? = null

    // 已渲染的气泡：消息 id -> TextView（按消息顺序）。用于流式输出时只追加增量文本
    private val bubbleViews = LinkedHashMap<Long, TextView>()

    private lateinit var fileStatusView: TextView
    private lateinit var eventStatusView: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var promptInput: EditText
    private lateinit var importButton: Button
    private lateinit var initButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var releaseButton: Button
    private lateinit var memoryCountView: TextView
    private lateinit var memoryToggle: Button
    private lateinit var learnToggle: Button
    private lateinit var manageMemoryButton: Button
    private var memoryDialog: AlertDialog? = null

    private val pickModelFolder =
        registerForActivityResult(PersistableOpenDocumentTree()) { uri ->
            if (uri != null) onModelFolderPicked(uri)
        }

    companion object {
        private const val KEY_DRAFT = "draft_text"
        private const val KEY_PANEL = "panel_expanded"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        p = Palette(isDarkMode())
        window.setBackgroundDrawable(ColorDrawable(p.bg))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildScreen()

        // 纯界面状态（输入框草稿、面板展开）随 Activity 重建恢复；其余一切来自 ViewModel
        if (savedInstanceState != null) {
            promptInput.setText(savedInstanceState.getString(KEY_DRAFT).orEmpty())
            promptInput.setSelection(promptInput.length())
            setPanelExpanded(savedInstanceState.getBoolean(KEY_PANEL, true), animate = false)
        }
        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DRAFT, promptInput.text.toString())
        outState.putBoolean(KEY_PANEL, panelExpanded)
    }

    // =====================================================================
    //  订阅 ViewModel
    // =====================================================================
    private fun observeViewModel() {
        lifecycleScope.launch {
            // STARTED 以下自动停止收集；回到前台时 StateFlow 会立刻回放最新值，界面追平后台期间的进度
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.phase.collect { renderPhase(it) } }
                launch { vm.messages.collect { renderMessages(it) } }
                launch { vm.events.collect { eventStatusView.text = it.joinToString("\n") } }
                launch { vm.modelFiles.collect { renderModelFiles(it) } }
                launch {
                    vm.memoryEnabled.collect {
                        memoryToggle.text = if (it) "记忆 · 开" else "记忆 · 关"
                        learnToggle.isEnabled = it            // 总开关关闭时，自学习没有意义
                    }
                }
                launch { vm.learningEnabled.collect { learnToggle.text = if (it) "自学习 · 开" else "自学习 · 关" } }
                launch { vm.memories.collect { memoryCountView.text = "已保存 ${it.size} 条长期记忆" } }
                launch {
                    vm.toasts.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun onModelFolderPicked(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure {
            vm.logEvent("无法保留文件夹读取权限：${it.message}")
        }
        vm.importModel(treeUri)
    }

    private fun onSendClicked() {
        if (vm.sendMessage(promptInput.text.toString())) {
            promptInput.setText("")
            stickToBottom = true
            scrollToBottom()
        }
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
            setOnClickListener { vm.newChat() }
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
        importButton = pill("导入模型", filled = false).apply { setOnClickListener { pickModelFolder.launch(null) } }
        initButton = pill("初始化", filled = true).apply { setOnClickListener { vm.initModel() } }
        releaseButton = pill("释放", filled = false).apply {
            isEnabled = false
            setOnClickListener { vm.releaseModel() }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(importButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(initButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(releaseButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        memoryCountView = TextView(this).apply {
            textSize = 12f
            setTextColor(p.text)
            text = "长期记忆：加载中…"
        }
        memoryToggle = pill("记忆 · 开", filled = false).apply {
            setOnClickListener { vm.setMemoryEnabled(!vm.memoryEnabled.value) }
        }
        learnToggle = pill("自学习 · 开", filled = false).apply {
            setOnClickListener { vm.setLearningEnabled(!vm.learningEnabled.value) }
        }
        manageMemoryButton = pill("管理记忆", filled = false).apply { setOnClickListener { showMemoryDialog() } }
        val memoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(memoryToggle, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(learnToggle, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(manageMemoryButton, LinearLayout.LayoutParams(0, dp(44), 1f))
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
            addView(sectionLabel("长期记忆"), matchWrap().apply { topMargin = dp(16) })
            addView(memoryCountView, matchWrap().apply { topMargin = dp(6) })
            addView(memoryRow, matchWrap().apply { topMargin = dp(10) })
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
        val send = fab("↑", p.accent, p.onAccent, "发送").apply { setOnClickListener { onSendClicked() } }
        val stop = fab("■", p.text, p.bg, "停止").apply { setOnClickListener { vm.stopGeneration() } }
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
    //  长期记忆管理：查看 / 置顶 / 删除 / 清空
    //  自动学习难免记错，用户必须能看到并纠正 AI 记住了什么。
    // =====================================================================
    private fun showMemoryDialog() {
        memoryDialog?.dismiss()
        val items = vm.memories.value
        if (items.isEmpty()) {
            memoryDialog = AlertDialog.Builder(this)
                .setTitle("长期记忆")
                .setMessage("还没有记忆。\n你可以直接说“记住：……”，开启自学习后我也会从对话里整理。")
                .setPositiveButton("好", null)
                .show()
            return
        }
        val labels = items.map { (if (it.pinned) "📌 " else "") + "[${it.kind.label}] ${it.content}" }.toTypedArray()
        memoryDialog = AlertDialog.Builder(this)
            .setTitle("长期记忆（${items.size}）")
            .setItems(labels) { _, which -> showMemoryActions(items[which]) }
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空") { _, _ -> confirmClearMemories() }
            .show()
    }

    private fun showMemoryActions(record: MemoryRecord) {
        memoryDialog?.dismiss()
        memoryDialog = AlertDialog.Builder(this)
            .setMessage(record.content)
            .setPositiveButton("删除") { _, _ -> vm.deleteMemory(record.id) }
            .setNeutralButton(if (record.pinned) "取消置顶" else "置顶（总是参考）") { _, _ ->
                vm.setMemoryPinned(record.id, !record.pinned)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearMemories() {
        memoryDialog?.dismiss()
        memoryDialog = AlertDialog.Builder(this)
            .setTitle("清空全部长期记忆？")
            .setMessage("此操作不可撤销。")
            .setPositiveButton("清空") { _, _ -> vm.clearMemories() }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        memoryDialog?.dismiss()        // 旋转 / 退出时避免窗口泄漏
        super.onDestroy()
    }

    // =====================================================================
    //  渲染：把 ViewModel 的状态映射到视图
    // =====================================================================
    private fun renderPhase(phase: ModelPhase) {
        val (color, label) = when (phase) {
            ModelPhase.IMPORTING -> p.busy to "正在导入模型…"
            ModelPhase.INITIALIZING -> p.busy to "正在初始化…"
            ModelPhase.RELEASING -> p.busy to "正在释放…"
            ModelPhase.GENERATING -> p.ok to "生成中…"
            ModelPhase.READY -> p.ok to "模型就绪 · 离线运行"
            ModelPhase.UNLOADED -> p.bad to "模型未就绪 · 点击标题展开"
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusSubtitle.text = label
        emptyHint.text = if (phase == ModelPhase.READY || phase == ModelPhase.GENERATING)
            "输入问题，开始对话" else "点击上方「LocalAI ▾」，导入并初始化模型"

        // 按钮可用性完全由阶段推导
        val unloaded = phase == ModelPhase.UNLOADED
        importButton.isEnabled = unloaded
        initButton.isEnabled = unloaded
        releaseButton.isEnabled = phase == ModelPhase.READY || phase == ModelPhase.GENERATING
        sendButton.isEnabled = phase == ModelPhase.READY
        stopButton.isEnabled = phase == ModelPhase.GENERATING   // 同时负责"发送/停止"互换显示

        // 面板自动展开/收起只在阶段发生变化时触发；Activity 重建后的首次渲染不动面板
        val previous = lastPhase
        lastPhase = phase
        if (previous != null && previous != phase) {
            when {
                previous == ModelPhase.INITIALIZING && phase == ModelPhase.READY -> setPanelExpanded(false)
                phase == ModelPhase.UNLOADED -> setPanelExpanded(true)   // 初始化失败 / 释放完成
            }
        }
    }

    private fun renderModelFiles(state: ModelFilesState) {
        val lines = mutableListOf("模型目录：", state.dirPath, "", "必需文件：")
        state.files.forEach {
            lines += if (it.present) "✓ ${it.name}（${it.sizeBytes} bytes）" else "✗ ${it.name}：缺失"
        }
        fileStatusView.text = lines.joinToString("\n")
    }

    /** 增量同步：消息列表是已渲染列表的延伸时只新增/追加；否则（清空、释放）整体重建。 */
    private fun renderMessages(list: List<ChatMessage>) {
        val renderedIds = bubbleViews.keys.toList()
        val isExtension = renderedIds.size <= list.size &&
            renderedIds.indices.all { renderedIds[it] == list[it].id }
        if (!isExtension) {
            chatContainer.removeAllViews()
            bubbleViews.clear()
        }
        for (msg in list) {
            val view = bubbleViews.getOrPut(msg.id) {
                when (msg.role) {
                    ChatRole.USER -> addUserBubble(msg.text)
                    ChatRole.ASSISTANT -> addAssistantBubble()
                }
            }
            bindBubble(view, msg)
        }
        updateEmptyState()
        if (stickToBottom) scrollToBottom()
    }

    private fun bindBubble(view: TextView, msg: ChatMessage) {
        val shown = view.length()
        if (msg.text.length > shown) {
            view.append(msg.text, shown, msg.text.length)      // 流式：只追加新增部分
        } else if (msg.text.length < shown) {
            view.text = msg.text
        }
        if (msg.role == ChatRole.ASSISTANT) {
            view.hint = if (msg.isStreaming) "思考中…" else "（无输出）"
            // 流式输出期间不开选择，结束后再允许复制
            val selectable = !msg.isStreaming
            if (view.isTextSelectable != selectable) view.setTextIsSelectable(selectable)
        }
    }

    // =====================================================================
    //  界面辅助：面板 / 气泡 / 滚动
    // =====================================================================
    private fun setPanelExpanded(expanded: Boolean, animate: Boolean = true) {
        panelExpanded = expanded
        if (animate) TransitionManager.beginDelayedTransition(rootLayout)
        panelScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        chevronView.text = if (expanded) "▴" else "▾"
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (chatContainer.childCount == 0) View.VISIBLE else View.GONE
    }

    private fun addUserBubble(text: String): TextView {
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
        return bubble
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun wrapWrap() = LinearLayout.LayoutParams(WRAP, WRAP)
}
