package io.github.helloktz.docreader

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val paper by lazy { getColor(R.color.reader_background) }
    private val ink by lazy { getColor(R.color.reader_ink) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var title: TextView
    private lateinit var export: ImageButton
    private lateinit var back: ImageButton
    private lateinit var footer: ReaderBar
    private lateinit var lockButton: ImageButton
    private lateinit var fitButton: ImageButton
    private lateinit var resetButton: ImageButton
    private lateinit var fitProgress: ProgressBar
    private lateinit var outlineButton: ImageButton
    private lateinit var pagesButton: ImageButton
    private lateinit var pdf: PdfCanvas
    private lateinit var page: TextView
    private var currentPdf: File? = null
    private var currentName = ""
    private var fromDocx = false
    private var busy = false
    private var ticket = 0
    private var sourceUri: Uri? = null
    private var exportBusy = false
    private var exportSnapshot: File? = null
    private var pendingExport = false
    private var navigation: NavigationPanel? = null
    private var stopped = false
    private val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        sourceUri = savedInstanceState?.getString("source")?.let(Uri::parse)
        pendingExport = savedInstanceState?.getBoolean("pendingExport") ?: false
        val restored = savedInstanceState?.getString("pdf")?.let(::File)
        if (restored?.isFile == true) showPdf(restored, savedInstanceState.getString("name") ?: "文档", savedInstanceState.getBoolean("docx"), savedInstanceState)
        else if (!openIntent(intent)) home()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink); setPadding(dp(24), dp(16), dp(24), dp(16)); minHeight = dp(64)
        background = ReaderUi.background(this@MainActivity, outlined = true, radius = 20)
        stateListAnimator = null
        val folder = getDrawable(Icon.OPEN.resource)!!.apply { setBounds(0, 0, dp(28), dp(28)) }
        setCompoundDrawablesRelative(folder, null, null, null); compoundDrawablePadding = dp(12)
        setOnClickListener { action() }
    }

    private fun icon(icon: Icon, name: String, emphasized: Boolean = false, action: () -> Unit) =
        ReaderUi.icon(this, icon, name, emphasized, action)

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
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        back = icon(Icon.BACK, "返回") { if (navigation != null) closeNavigation() else home() }
        title = label("", 16f).apply {
            setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(4), 0, dp(12), 0)
        }
        export = icon(Icon.EXPORT, "导出 PDF", emphasized = true) { chooseExport() }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        title.gravity = Gravity.CENTER_VERTICAL
        toolbar.addView(export, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        resetButton = icon(Icon.RESET, "恢复默认") { pdf.resetView() }
        fitButton = icon(Icon.FIT, "适应文字") { pdf.fitText() }
        fitProgress = ProgressBar(this).apply {
            visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.reader_accent))
        }
        val fitSlot = FrameLayout(this).apply {
            addView(fitButton, FrameLayout.LayoutParams(-1, -1))
            addView(fitProgress, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        lockButton = icon(Icon.UNLOCK, "锁定尺寸") { pdf.setSizeLocked(!pdf.isSizeLocked) }
        outlineButton = icon(Icon.OUTLINE, "大纲") { toggleNavigation(0) }
        pagesButton = icon(Icon.PAGES, "页面预览") { toggleNavigation(1) }
        page = label("", 12f, getColor(R.color.reader_muted)).apply {
            gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(6), dp(10), dp(6)); minHeight = dp(32)
            fontFeatureSettings = "tnum"; background = ReaderUi.shape(this@MainActivity, paper, 12)
        }
        footer = ReaderBar(this, listOf(resetButton, fitSlot, lockButton, outlineButton, pagesButton), page)
        root.addView(footer)
        pdf = PdfCanvas(this).apply {
            contentDescription = "PDF 页面"
            onPageChanged = { current, total ->
                page.text = getString(R.string.page_position, current, total); page.contentDescription = page.text
                page.minWidth = kotlin.math.ceil(page.paint.measureText(getString(R.string.page_position, total, total))).toInt() + page.paddingLeft + page.paddingRight
            }
            onFailure = { message -> if (!stopped) failure(message) }
            onOutlineReady = { navigation?.refreshOutline() }
            onLockChanged = { updateLockIcon() }
            onViewportChanged = { updateViewportButtons() }
            onTextFitBusy = { active ->
                fitProgress.visibility = if (active) View.VISIBLE else View.GONE
                fitButton.imageAlpha = if (active) 0 else 255
                updateViewportButtons()
            }
            onTextFitError = { message -> Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(0) {
            if (navigation != null) closeNavigation() else if (currentPdf != null || busy) home() else finish()
        }
    }

    private fun updateLockIcon() {
        lockButton.setImageResource((if (pdf.isSizeLocked) Icon.LOCK else Icon.UNLOCK).resource)
        lockButton.isSelected = pdf.isSizeLocked
        lockButton.stateDescription = if (pdf.isSizeLocked) "已锁定" else "未锁定"
        lockButton.contentDescription = if (pdf.isSizeLocked) "解锁尺寸" else "锁定尺寸"
        lockButton.tooltipText = lockButton.contentDescription
        updateViewportButtons()
    }

    private fun updateViewportButtons() {
        resetButton.isEnabled = !pdf.isSizeLocked
        resetButton.alpha = if (pdf.isSizeLocked) .35f else 1f
        resetButton.isSelected = pdf.isDefaultView && !pdf.isTextFitted
        fitButton.isEnabled = !pdf.isSizeLocked && !pdf.isTextFitBusy
        fitButton.alpha = if (pdf.isSizeLocked) .35f else 1f
        fitButton.isSelected = pdf.isTextFitted
        fitButton.stateDescription = if (pdf.isTextFitBusy) "正在计算" else if (pdf.isTextFitted) "已适应文字" else null
    }

    private fun toggleNavigation(mode: Int) {
        val existing = navigation
        if (existing?.mode == mode) { closeNavigation(); return }
        if (existing != null) existing.select(mode)
        else {
            navigation = NavigationPanel(this, pdf, { updateNavigationState() }) { closeNavigation() }.also {
                content.addView(it, FrameLayout.LayoutParams(-1, -1)); it.select(mode)
            }
        }
        updateNavigationState()
    }

    private fun updateNavigationState() {
        outlineButton.isSelected = navigation?.mode == 0
        pagesButton.isSelected = navigation?.mode == 1
        pdf.importantForAccessibility = if (navigation == null) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun closeNavigation() {
        navigation?.let { it.close(); content.removeView(it) }; navigation = null
        updateNavigationState()
    }

    private fun home() {
        root.setBackgroundColor(paper)
        val previous = currentPdf
        if (previous != null && previous != exportSnapshot) io.execute { previous.delete() }
        closeNavigation(); sourceUri = null
        ticket++; busy = false; currentPdf = null
        pdf.closeDocument()
        (pdf.parent as? android.view.ViewGroup)?.removeView(pdf)
        toolbar.visibility = View.GONE
        footer.visibility = View.GONE
        content.removeAllViews()
        content.addView(button("打开文件") { chooseFile() },
            FrameLayout.LayoutParams(dp(208), -2, Gravity.CENTER))
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
        root.setBackgroundColor(Color.WHITE)
        val previous = currentPdf
        if (previous != null && previous != exportSnapshot) io.execute { previous.delete() }
        closeNavigation()
        ticket++; busy = true; currentPdf = null; pdf.closeDocument()
        (pdf.parent as? android.view.ViewGroup)?.removeView(pdf)
        content.removeAllViews(); back.visibility = View.VISIBLE; export.visibility = View.GONE
        title.text = name; page.text = ""
        toolbar.visibility = View.VISIBLE; footer.visibility = View.GONE
        val loading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        loading.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        loading.addView(label("正在打开…", 15f).apply { setPadding(0, dp(18), 0, 0) })
        content.addView(loading, FrameLayout.LayoutParams(-1, -1))
        return ticket
    }

    private fun openUri(uri: Uri) {
        sourceUri = uri
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
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

    private fun showPdf(file: File, name: String, docx: Boolean, position: Bundle? = null) {
        root.setBackgroundColor(Color.WHITE)
        currentPdf = file; currentName = name; fromDocx = docx; busy = true
        title.text = name; back.visibility = View.VISIBLE; export.visibility = View.GONE
        toolbar.visibility = View.VISIBLE; footer.visibility = View.VISIBLE
        content.removeAllViews()
        content.addView(pdf, FrameLayout.LayoutParams(-1, -1))
        val id = ticket
        pdf.open(file) {
            if (id == ticket && !stopped) {
                busy = false; export.visibility = if (docx) View.VISIBLE else View.GONE
                pdf.restorePosition(position); updateLockIcon()
                if (pendingExport && Environment.isExternalStorageManager()) { pendingExport = false; chooseExport() }
            }
        }
    }

    private fun chooseExport() {
        val snapshot = currentPdf ?: return
        val uri = sourceUri ?: run { exportError("请重新打开原 DOCX 后导出"); return }
        if (exportBusy || busy || !fromDocx) return
        val privateFile = uri.scheme == "file" && uri.path?.startsWith(applicationInfo.dataDir + File.separator) == true
        if (!privateFile && !Environment.isExternalStorageManager()) {
            pendingExport = true
            startActivityForResult(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")), 12)
            return
        }
        exportBusy = true; exportSnapshot = snapshot; export.isEnabled = false
        io.execute {
            try {
                val source = SameFolderExport.sourceFile(this, uri) ?: throw IOException("此文件来源未提供本地目录，无法导出")
                require(source.isFile) { "原文件已移动或无法访问" }
                val target = SameFolderExport.destination(source)
                require(target.canonicalFile != source.canonicalFile) { "无法覆盖原文件，请检查 DOCX 后缀名" }
                val exists = target.exists()
                runOnUiThread {
                    if (stopped) return@runOnUiThread
                    if (exists) AlertDialog.Builder(this).setTitle(target.name)
                        .setPositiveButton("覆盖") { _, _ -> writeExport(snapshot, source, true) }
                        .setNegativeButton("都保存") { _, _ -> writeExport(snapshot, source, false) }
                        .setNeutralButton("取消") { _, _ -> exportFinished() }
                        .setOnCancelListener { exportFinished() }.show()
                    else writeExport(snapshot, source, false)
                }
            } catch (e: Exception) { runOnUiThread { if (!stopped) { exportFinished(); exportError(e.message) } } }
        }
    }

    private fun writeExport(snapshot: File, source: File, overwrite: Boolean) {
        io.execute {
            try {
                SameFolderExport.save(snapshot, source, overwrite)
                runOnUiThread { if (!stopped) { exportFinished(); Toast.makeText(this, "PDF 已保存", Toast.LENGTH_SHORT).show() } }
            } catch (e: Exception) { runOnUiThread { if (!stopped) { exportFinished(); exportError(e.message) } } }
        }
    }

    private fun exportFinished() {
        val snapshot = exportSnapshot; exportSnapshot = null
        if (snapshot != null && snapshot != currentPdf) io.execute { snapshot.delete() }
        exportBusy = false; export.isEnabled = true
    }
    private fun exportError(message: String?) {
        AlertDialog.Builder(this).setTitle("导出失败").setMessage(message).setPositiveButton("知道了", null).show()
    }

    @Deprecated("Framework activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 12) {
            if (pendingExport) {
                pendingExport = false
                if (Environment.isExternalStorageManager()) {
                    if (busy) pendingExport = true else chooseExport()
                } else exportError("未获得文件访问权限")
            }
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 10) openUri(uri)
    }

    private fun failure(message: String) {
        home()
        AlertDialog.Builder(this).setTitle("无法打开").setMessage(message).setPositiveButton("知道了", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPdf?.let { outState.putString("pdf", it.absolutePath) }
        outState.putString("name", currentName); outState.putBoolean("docx", fromDocx)
        sourceUri?.let { outState.putString("source", it.toString()) }
        outState.putBoolean("pendingExport", pendingExport)
        pdf.savePosition(outState)
    }

    override fun onDestroy() {
        stopped = true; ticket++; pdf.dispose(); io.shutdown()
        super.onDestroy()
    }
}
