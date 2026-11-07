package io.github.helloktz.docreader

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val paper = Color.rgb(246, 245, 240)
    private val ink = Color.rgb(29, 48, 41)
    private val green = Color.rgb(36, 92, 76)
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private lateinit var export: Button
    private lateinit var back: Button
    private lateinit var pdf: PdfCanvas
    private lateinit var page: TextView
    private var currentPdf: File? = null
    private var currentName = ""
    private var fromDocx = false
    private var busy = false
    private var ticket = 0
    private var exportSource: File? = null
    private var stopped = false
    private val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val restored = savedInstanceState?.getString("pdf")?.let(::File)
        if (restored?.isFile == true) showPdf(restored, savedInstanceState.getString("name") ?: "文档", savedInstanceState.getBoolean("docx"))
        else if (!openIntent(intent)) home()
        exportSource = savedInstanceState?.getString("exportSource")?.let(::File)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) Color.WHITE else green)
        background = GradientDrawable().apply { setColor(if (primary) green else Color.TRANSPARENT); cornerRadius = dp(14).toFloat() }
        setOnClickListener { action() }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.setDecorFitsSystemWindows(false)
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        back = button("‹ 返回") { home() }
        title = label("文阅", 18f).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(null, Typeface.BOLD) }
        export = button("导出 PDF") { chooseExport() }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(76), dp(48)))
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        title.gravity = Gravity.CENTER_VERTICAL
        toolbar.addView(export, LinearLayout.LayoutParams(dp(100), dp(48)))
        root.addView(toolbar)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        page = label("", 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(page)
        pdf = PdfCanvas(this).apply {
            contentDescription = "PDF 页面，双指缩放，滑动阅读，双击放大或还原"
            onPageChanged = { current, total -> page.text = getString(R.string.page_position, current, total) }
            onFailure = { message -> if (!stopped) failure(message) }
        }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { if (currentPdf != null || busy) home() else finish() }
    }

    private fun home() {
        val previous = currentPdf
        if (previous != null && previous != exportSource) io.execute { previous.delete() }
        ticket++; busy = false; currentPdf = null
        pdf.closeDocument()
        (pdf.parent as? android.view.ViewGroup)?.removeView(pdf)
        title.text = "文阅"; back.visibility = View.GONE; export.visibility = View.GONE; page.text = "离线阅读 · 原文件保持只读"
        content.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        card.addView(label("文", 58f, green).apply { setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(100)))
        card.addView(label("打开，即阅读。", 28f).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) })
        card.addView(label("DOCX 与 PDF，专注眼前的文字。", 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(32)) })
        card.addView(button("打开文件", true) { chooseFile() }, LinearLayout.LayoutParams(-1, dp(54)))
        card.addView(button("体验示例文档") { openDemo() }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })
        card.addView(button("开源许可") {
            val text = label(assets.open("open-source-licenses.txt").bufferedReader().use { it.readText() }, 12f)
            text.setPadding(dp(20), dp(16), dp(20), dp(16))
            val scroll = ScrollView(this).apply { addView(text) }
            AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("关闭", null).show()
        }, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    private fun chooseFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", docxMime))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, 10)
    }

    private fun openIntent(source: Intent?): Boolean {
        val uri = when (source?.action) {
            Intent.ACTION_VIEW -> source.data
            Intent.ACTION_SEND -> source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return false
        openUri(uri)
        return true
    }

    private fun loading(name: String): Int {
        val previous = currentPdf
        if (previous != null && previous != exportSource) io.execute { previous.delete() }
        ticket++; busy = true; currentPdf = null; pdf.closeDocument()
        (pdf.parent as? android.view.ViewGroup)?.removeView(pdf)
        content.removeAllViews(); back.visibility = View.VISIBLE; export.visibility = View.GONE
        title.text = name; page.text = ""
        val loading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        loading.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        loading.addView(label("正在打开…", 15f).apply { setPadding(0, dp(18), 0, 0) })
        content.addView(loading, FrameLayout.LayoutParams(-1, -1))
        return ticket
    }

    private fun openUri(uri: Uri) {
        val id = loading("打开文件")
        io.execute {
            try {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "文档"
                val input = File.createTempFile("input-", ".bin", cacheDir)
                try {
                    contentResolver.openInputStream(uri)?.use { source ->
                        input.outputStream().use { target ->
                            val buffer = ByteArray(64 * 1024); var total = 0L
                            while (true) {
                                val n = source.read(buffer); if (n < 0) break
                                total += n
                                if (total > 256L * 1024 * 1024) throw IOException("文件超过 256 MB，暂时无法打开")
                                target.write(buffer, 0, n)
                            }
                        }
                    } ?: throw IOException("无法读取所选文件")
                    prepare(input, name, id)
                } finally { input.delete() }
            } catch (e: Exception) { runOnUiThread { if (id == ticket && !stopped) failure(e.message ?: "无法打开文件") } }
        }
    }

    private fun prepare(input: File, name: String, id: Int) {
        val header = ByteArray(5)
        val count = input.inputStream().use { it.read(header) }
        val isPdf = count == 5 && String(header, Charsets.US_ASCII) == "%PDF-"
        val isDocx = !isPdf && (name.endsWith(".docx", true) || (count >= 2 && header[0] == 80.toByte() && header[1] == 75.toByte()))
        if (!isPdf && !isDocx) throw IOException("仅支持 DOCX 和 PDF 文件")
        val output = File.createTempFile("document-", ".pdf", cacheDir)
        try {
            if (isPdf) input.copyTo(output, overwrite = true)
            else NativeBridge.convert(input.absolutePath, output.absolutePath)?.let { throw IOException(it) }
            runOnUiThread {
                if (id == ticket && !stopped) showPdf(output, name, isDocx)
                else output.delete()
            }
        } catch (e: Exception) { output.delete(); throw e }
    }

    private fun showPdf(file: File, name: String, docx: Boolean) {
        currentPdf = file; currentName = name; fromDocx = docx; busy = true
        title.text = name; back.visibility = View.VISIBLE; export.visibility = View.GONE
        content.removeAllViews()
        content.addView(pdf, FrameLayout.LayoutParams(-1, -1))
        val id = ticket
        pdf.open(file) {
            if (id == ticket && !stopped) {
                busy = false; export.visibility = if (docx) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openDemo() {
        val id = loading("示例文档.docx")
        io.execute {
            val input = File.createTempFile("demo-", ".docx", cacheDir)
            try {
                assets.open("sample.docx").use { source -> input.outputStream().use { source.copyTo(it) } }
                prepare(input, "示例文档.docx", id)
            } catch (e: Exception) { runOnUiThread { if (id == ticket && !stopped) failure(e.message ?: "无法打开示例") } }
            finally { input.delete() }
        }
    }

    private fun chooseExport() {
        exportSource = currentPdf ?: return
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, currentName.substringBeforeLast('.', currentName) + ".pdf")
        }, 11)
    }

    @Deprecated("Framework activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { if (requestCode == 11) exportSource = null; return }
        val uri = data?.data ?: return
        if (requestCode == 10) openUri(uri)
        if (requestCode == 11) {
            val source = exportSource ?: return
            exportSource = null
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { target -> source.inputStream().use { it.copyTo(target) } }
                        ?: throw IOException("无法写入所选位置")
                    runOnUiThread { if (!stopped) Toast.makeText(this, "PDF 已保存", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    runOnUiThread { if (!stopped) AlertDialog.Builder(this).setTitle("导出失败").setMessage(e.message).setPositiveButton("知道了", null).show() }
                }
            }
        }
    }

    private fun failure(message: String) {
        home()
        AlertDialog.Builder(this).setTitle("无法打开").setMessage(message).setPositiveButton("知道了", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPdf?.let { outState.putString("pdf", it.absolutePath) }
        outState.putString("name", currentName); outState.putBoolean("docx", fromDocx)
        exportSource?.let { outState.putString("exportSource", it.absolutePath) }
    }

    override fun onDestroy() {
        stopped = true; ticket++; pdf.dispose(); io.shutdown()
        super.onDestroy()
    }
}
