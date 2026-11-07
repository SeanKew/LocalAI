package com.seankew.localai

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Typeface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private lateinit var promptInput: EditText
    private lateinit var initButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var releaseButton: Button
    private var llm: MnnLlm? = null
    private val modelDir: File
        get() = File(getExternalFilesDir(null) ?: filesDir, "qwen2.5-1.5b-mnn")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshModelStatus()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "LocalAI · 本地模型测试"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())

        statusView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusView, matchWrap())

        initButton = Button(this).apply {
            text = "1. 初始化模型"
            setOnClickListener { initializeModel() }
        }
        root.addView(initButton, matchWrap())

        promptInput = EditText(this).apply {
            hint = "输入要问模型的问题"
            setText("请用中文简单介绍你自己。")
            minLines = 2
            maxLines = 4
            textSize = 16f
        }
        root.addView(promptInput, matchWrap())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sendButton = Button(this).apply {
            text = "发送"
            isEnabled = false
            setOnClickListener { sendPrompt() }
        }
        stopButton = Button(this).apply {
            text = "停止"
            isEnabled = false
            setOnClickListener { llm?.stop(); appendStatus("已请求停止生成。") }
        }
        actions.addView(sendButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(stopButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actions, matchWrap())

        releaseButton = Button(this).apply {
            text = "释放模型"
            isEnabled = false
            setOnClickListener { releaseModel() }
        }
        root.addView(releaseButton, matchWrap())
        root.addView(TextView(this).apply {
            text = "模型输出"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(6))
        }, matchWrap())

        outputView = TextView(this).apply {
            text = "尚未开始推理。"
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val outputScroll = ScrollView(this).apply { addView(outputView) }
        root.addView(outputScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun refreshModelStatus() {
        val names = listOf("config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt")
        val lines = mutableListOf("模型目录：", modelDir.absolutePath, "", "模型文件检查：")
        names.forEach { name ->
            val modelFile = File(modelDir, name)
            lines += if (modelFile.isFile && modelFile.length() > 0L) {
                "✓ $name  (${modelFile.length()} bytes)"
            } else {
                "✗ $name  缺失"
            }
        }
        lines += ""
        lines += "请先把模型文件放进上述目录，再点“初始化模型”。"
        statusView.text = lines.joinToString("\n")
        if (!modelDir.exists()) modelDir.mkdirs()
    }

    private fun initializeModel() {
        if (llm != null) {
            appendStatus("模型对象已存在；如需重新初始化，请先释放模型。")
            return
        }
        initButton.isEnabled = false
        appendStatus("正在加载 MNN 原生库并初始化模型，请耐心等待……")
        val engine = MnnLlm(applicationContext)
        llm = engine
        uiScope.launch {
            val result = engine.init(modelDir = modelDir, threads = 4, backend = "cpu", precision = "low")
            result.fold(
                onSuccess = {
                    appendStatus("模型初始化成功，可以发送问题。")
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                    releaseButton.isEnabled = true
                    refreshModelStatus()
                },
                onFailure = { error ->
                    appendStatus("初始化失败：${error.message ?: error.javaClass.simpleName}")
                    llm = null
                    engine.release()
                    initButton.isEnabled = true
                    refreshModelStatus()
                }
            )
        }
    }

    private fun sendPrompt() {
        val engine = llm ?: return
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty()) {
            appendStatus("请先输入问题。")
            return
        }
        sendButton.isEnabled = false
        stopButton.isEnabled = true
        outputView.text = ""
        appendStatus("开始生成……")
        uiScope.launch {
            try {
                engine.chat(prompt, maxNewTokens = 256).collect { piece ->
                    outputView.append(piece)
                }
                appendStatus("生成结束：${engine.lastFinishReason ?: "未知原因"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appendStatus("生成失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                sendButton.isEnabled = engine.isReady
                stopButton.isEnabled = false
            }
        }
    }

    private fun releaseModel() {
        val engine = llm ?: return
        sendButton.isEnabled = false
        stopButton.isEnabled = false
        releaseButton.isEnabled = false
        appendStatus("正在释放模型资源……")
        engine.release()
        uiScope.launch {
            runCatching { engine.awaitReleased() }
            if (llm === engine) llm = null
            appendStatus("模型资源已释放。")
            initButton.isEnabled = true
            refreshModelStatus()
        }
    }

    private fun appendStatus(message: String) {
        statusView.append("\n\n$message")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    override fun onDestroy() {
        llm?.release()
        llm = null
        uiScope.cancel()
        super.onDestroy()
    }
}
