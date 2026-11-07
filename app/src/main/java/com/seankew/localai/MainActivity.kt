package com.seankew.localai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Typeface
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

class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var fileStatusView: TextView
    private lateinit var eventStatusView: TextView
    private lateinit var outputView: TextView
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
        buildScreen()
        refreshModelStatus()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(TextView(this).apply {
            text = "LocalAI · 本地模型测试"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "模型文件状态"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }, matchWrap())

        fileStatusView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(fileStatusView, matchWrap())

        importButton = Button(this).apply {
            text = "导入模型文件夹"
            setOnClickListener { chooseModelFolder() }
        }
        root.addView(importButton, matchWrap())

        initButton = Button(this).apply {
            text = "初始化模型"
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
            setOnClickListener {
                llm?.stop()
                appendEvent("已请求停止生成。")
            }
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
            text = "运行状态"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }, matchWrap())

        eventStatusView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            text = "先导入模型文件，再初始化。"
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(eventStatusView, matchWrap())

        root.addView(TextView(this).apply {
            text = "模型输出"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }, matchWrap())

        outputView = TextView(this).apply {
            text = "尚未开始推理。"
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        outputScroll = ScrollView(this).apply {
            isFillViewport = false
            isNestedScrollingEnabled = false
            addView(outputView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(outputScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
        ))

        setContentView(pageScroll)
    }

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
                        appendEvent("必需文件已齐全。现在可以点击“初始化模型”。")
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
            val result = engine.init(modelDir = modelDir, threads = 4, backend = "cpu", precision = "low")
            result.fold(
                onSuccess = {
                    appendEvent("✓ 模型初始化成功。现在可以输入问题并点击“发送”。")
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                    releaseButton.isEnabled = true
                    initButton.isEnabled = false
                    importButton.isEnabled = false
                },
                onFailure = { error ->
                    appendEvent("初始化失败：${error.message ?: error.javaClass.simpleName}")
                    llm = null
                    engine.release()
                    initButton.isEnabled = true
                    importButton.isEnabled = true
                    refreshModelStatus()
                }
            )
        }
    }

    private fun sendPrompt() {
        val engine = llm
        if (engine == null || !engine.isReady) {
            appendEvent("模型尚未成功初始化，因此“发送”暂不可用。请先初始化模型。")
            sendButton.isEnabled = false
            return
        }
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty()) {
            appendEvent("请先输入问题。")
            return
        }

        sendButton.isEnabled = false
        stopButton.isEnabled = true
        outputView.text = ""
        appendEvent("开始生成……")
        uiScope.launch {
            try {
                engine.chat(prompt, maxNewTokens = 256).collect { piece ->
                    outputView.append(piece)
                    outputScroll.post { outputScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                appendEvent("生成结束：${engine.lastFinishReason ?: "未知原因"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appendEvent("生成失败：${error.message ?: error.javaClass.simpleName}")
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
        appendEvent("正在释放模型资源……")
        engine.release()
        uiScope.launch {
            runCatching { engine.awaitReleased() }
            if (llm === engine) llm = null
            appendEvent("模型资源已释放。")
            initButton.isEnabled = true
            importButton.isEnabled = true
            refreshModelStatus()
        }
    }

    private fun appendEvent(message: String) {
        eventStatusView.append("\n$message")
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
