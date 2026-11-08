package io.github.helloktz.docreader

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.util.LruCache
import android.view.*
import android.widget.OverScroller
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class OutlineEntry(val title: String, val page: Int, val level: Int)

/** All renderer access is serialized; zoom gestures reuse already rendered pages. */
class PdfCanvas(context: Context) : View(context) {
    private data class Size(val width: Int, val height: Int)
    private data class TextEdges(val left: Float, val right: Float)
    private class Document(val file: File, val renderer: PdfRenderer, val sizes: List<Size>) {
        var textScanned = false
        var textEdges: TextEdges? = null
        var textRows: Array<FloatArray>? = null
    }
    private data class TextAnalysis(val edges: TextEdges?, val rows: Array<FloatArray>?)
    private val worker = Executors.newSingleThreadExecutor()
    private val textWorker = Executors.newSingleThreadExecutor()
    private var textRequest: AtomicBoolean? = null
    private var marginMode = false
    private val main = Handler(Looper.getMainLooper())
    private fun bitmapCache(bytes: Int) = object : LruCache<Long, Bitmap>(bytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val base = bitmapCache(32 * 1024 * 1024)
    private val detail = bitmapCache(48 * 1024 * 1024)
    private data class Tile(val rect: RectF, val requestedZoom: Float, val bitmap: Bitmap)
    private val tiles = object : LruCache<Int, Tile>(16 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Tile) = value.bitmap.allocationByteCount
    }
    private val pendingTiles = mutableSetOf<Int>()
    private var touching = false
    private val pending = mutableSetOf<Long>()
    private val scroller = OverScroller(context).apply { setFriction(ViewConfiguration.getScrollFriction() * .7f) }
    private var document: Document? = null
    @Volatile private var generation = 0
    private var disposed = false
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var starts = FloatArray(0)
    private var heights = FloatArray(0)
    private var baseHeight = 0f
    private val gap = 8f * resources.displayMetrics.density
    private val gapPaint = Paint().apply { color = context.getColor(R.color.reader_border) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pageRect = RectF()
    private var pendingPosition: Bundle? = null
    var onPageChanged: ((Int, Int) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onOutlineReady: (() -> Unit)? = null
    var onLockChanged: (() -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null
    var onTextFitBusy: ((Boolean) -> Unit)? = null
    var onTextFitError: ((String) -> Unit)? = null
    var isTextFitted = false; private set
    val isDefaultView get() = zoom == 1f && offsetX == 0f
    val isTextFitBusy get() = textRequest != null
    var outlines: List<OutlineEntry> = emptyList(); private set
    var isSizeLocked = false; private set
    val pageCount get() = starts.size
    val currentPage get() = reportedPage.coerceAtLeast(0)
    val zoomLevel get() = zoom
    val horizontalOffset get() = offsetX
    val verticalOffset get() = offsetY
    private var reportedPage = -1

    fun open(file: File, ready: (Int) -> Unit) {
        closeDocument()
        val ticket = generation
        worker.execute {
            var renderer: PdfRenderer? = null
            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
                val sizes = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { Size(it.width, it.height) } }
                require(sizes.isNotEmpty()) { "PDF 没有可显示的页面" }
                val loaded = Document(file, renderer, sizes)
                main.post {
                    if (ticket != generation || disposed) worker.execute { loaded.renderer.close() }
                    else { document = loaded; rebuildLayout(); ready(sizes.size); invalidate() }
                }
            } catch (e: Exception) {
                renderer?.close()
                main.post { if (ticket == generation && !disposed) onFailure?.invoke(
                    if (e is SecurityException) "此 PDF 需要密码，当前版本暂不支持" else "无法打开 PDF：" + (e.message ?: "文件可能已损坏")) }
                return@execute
            }
            // Outline failure must never close an otherwise readable document.
            val entries = try {
                val json = JSONArray(NativeBridge.outline(file.absolutePath) ?: "[]")
                (0 until json.length()).map { i -> json.getJSONObject(i).let {
                    OutlineEntry(it.getString("title"), it.getInt("page"), it.getInt("level"))
                } }.filter { it.page in 0 until renderer.pageCount }
            } catch (_: Exception) { emptyList() }
            main.post { if (ticket == generation && !disposed) { outlines = entries; onOutlineReady?.invoke() } }
        }
    }

    fun closeDocument() {
        cancelTextFit(); isTextFitted = false; marginMode = false
        touching = false
        generation++; scroller.forceFinished(true)
        val old = document; document = null
        if (old != null) worker.execute { old.renderer.close() }
        base.evictAll(); detail.evictAll(); tiles.evictAll(); pending.clear(); pendingTiles.clear()
        zoom = 1f; offsetX = 0f; offsetY = 0f; isSizeLocked = false
        starts = FloatArray(0); heights = FloatArray(0); baseHeight = 0f
        outlines = emptyList(); reportedPage = -1; pendingPosition = null
        invalidate()
    }

    fun dispose() {
        disposed = true; closeDocument()
        textWorker.shutdown()
        worker.execute { main.post { worker.shutdown() } }
    }

    fun resetView() {
        if (isSizeLocked) return
        cancelTextFit(); isTextFitted = false; marginMode = false
        scroller.forceFinished(true)
        offsetY /= zoom; zoom = 1f; offsetX = 0f
        clampOffsets(); onViewportChanged?.invoke(); invalidate()
    }

    fun fitText() {
        if (isSizeLocked || width <= 0 || textRequest != null) return
        val doc = document ?: return
        if (doc.textScanned) {
            if (doc.textEdges == null) onTextFitError?.invoke("此文档没有可识别的文字") else applyTextFit()
            return
        }
        val request = AtomicBoolean(false); val ticket = generation
        textRequest = request; onTextFitBusy?.invoke(true)
        textWorker.execute {
            val result = runCatching {
                val values = NativeBridge.textBounds(doc.file.absolutePath, request) ?: error("无法读取文字边界")
                if (values.isEmpty()) TextAnalysis(null, null) else {
                    var position = 2
                    val rows = Array(doc.sizes.size) {
                        val count = values[position++].toInt()
                        check(count >= 0 && count <= (values.size - position) / 2)
                        FloatArray(count * 2) { values[position++].toFloat() }
                    }
                    check(position == values.size) { "页面数量不一致" }
                    TextAnalysis(TextEdges(values[0].toFloat(), values[1].toFloat()), rows)
                }
            }
            main.post {
                if (ticket != generation || textRequest !== request || disposed) return@post
                textRequest = null; onTextFitBusy?.invoke(false)
                result.onSuccess { analysis ->
                    doc.textEdges = analysis.edges
                    doc.textRows = analysis.rows
                    doc.textScanned = true
                    if (doc.textEdges == null) onTextFitError?.invoke("此文档没有可识别的文字") else applyTextFit()
                }.onFailure { onTextFitError?.invoke("无法完整读取此文档的文字边界") }
            }
        }
    }

    private fun cancelTextFit() {
        textRequest?.set(true)
        if (textRequest != null) { textRequest = null; onTextFitBusy?.invoke(false) }
    }

    private val textPadding get() = min(12f * resources.displayMetrics.density, width / 4f)
    private fun textFitZoom(edges: TextEdges) = (width - 2 * textPadding) / (width * (edges.right - edges.left))

    private fun textFocus(): Float {
        val top = offsetY / zoom
        val rows = document?.textRows ?: return top
        var nearest = Float.POSITIVE_INFINITY
        starts.indices.forEach { i ->
            for (j in rows[i].indices step 2) {
                val lineTop = starts[i] + rows[i][j] * width
                val lineBottom = starts[i] + rows[i][j + 1] * width
                if (lineBottom >= top && lineTop <= (offsetY + height) / zoom) nearest = min(nearest, max(top, lineTop))
            }
        }
        return if (nearest.isFinite()) nearest else top
    }

    private fun applyTextFit(anchorToText: Boolean = true) {
        val edges = document?.textEdges ?: return
        if (width <= 0) return
        val target = textFitZoom(edges)
        scroller.forceFinished(true)
        // Preserve the reading position for normal fitting. Very narrow text
        // needs its first visible line brought into view at high magnification.
        val relocate = anchorToText && target > 4f
        val focus = if (relocate) textFocus() else offsetY / zoom
        offsetY = focus * target - if (relocate) textPadding else 0f
        zoom = target; offsetX = edges.left * width * zoom - textPadding
        marginMode = true; isTextFitted = true
        clampOffsets(); onViewportChanged?.invoke(); invalidate()
    }

    fun setSizeLocked(locked: Boolean) {
        if (locked) cancelTextFit()
        isSizeLocked = locked; scroller.forceFinished(true); onLockChanged?.invoke()
    }

    fun jumpToPage(index: Int) {
        if (index !in starts.indices) return
        cancelTextFit()
        scroller.forceFinished(true); offsetY = starts[index] * zoom; clampOffsets(); invalidate()
    }

    fun savePosition(state: Bundle) {
        state.putFloat("zoom", zoom); state.putFloat("panX", offsetX / width.coerceAtLeast(1))
        state.putFloat("panY", offsetY / width.coerceAtLeast(1)); state.putBoolean("locked", isSizeLocked)
        state.putBoolean("textFitted", isTextFitted); state.putBoolean("marginMode", marginMode)
        document?.textEdges?.let { state.putFloat("textLeft", it.left); state.putFloat("textRight", it.right) }
    }

    fun restorePosition(state: Bundle?) {
        if (state == null) return
        if (width <= 0 || document == null) { pendingPosition = state; return }
        marginMode = state.getBoolean("marginMode")
        isTextFitted = state.getBoolean("textFitted")
        if (state.containsKey("textLeft")) document?.let {
            it.textEdges = TextEdges(state.getFloat("textLeft"), state.getFloat("textRight"))
            it.textScanned = it.textRows != null
        }
        zoom = state.getFloat("zoom", 1f)
        offsetX = state.getFloat("panX") * width; offsetY = state.getFloat("panY") * width
        isSizeLocked = state.getBoolean("locked"); onLockChanged?.invoke()
        if (isTextFitted) applyTextFit(false) else { clampOffsets(); onViewportChanged?.invoke(); invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val factor = if (oldw > 0) w.toFloat() / oldw else 1f
        offsetX *= factor; offsetY *= factor
        base.evictAll(); detail.evictAll(); tiles.evictAll(); rebuildLayout(); clampOffsets()
        if (isTextFitted) applyTextFit(false)
        pendingPosition?.let { pendingPosition = null; restorePosition(it) }
    }

    private fun rebuildLayout() {
        val sizes = document?.sizes ?: return
        starts = FloatArray(sizes.size); heights = FloatArray(sizes.size)
        var y = 0f
        sizes.forEachIndexed { i, size ->
            starts[i] = y; heights[i] = width.coerceAtLeast(1).toFloat() * size.height / size.width.coerceAtLeast(1)
            y += heights[i] + gap
        }
        baseHeight = max(0f, y - gap)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val doc = document ?: return
        if (width <= 0) return
        canvas.save(); canvas.translate(-offsetX, -offsetY); canvas.scale(zoom, zoom)
        val top = offsetY / zoom; val bottom = (offsetY + height) / zoom
        var current = 0
        for (i in starts.indices) {
            if (starts[i] <= top + height / zoom / 3) current = i
            val pageBottom = starts[i] + heights[i]
            // Draw only between pages, keeping the paper flush with both screen edges.
            if (i < starts.lastIndex && pageBottom < bottom && starts[i + 1] > top) {
                canvas.drawRect(0f, pageBottom, width.toFloat(), starts[i + 1], gapPaint)
            }
            if (pageBottom < top || starts[i] > bottom) continue
            val rect = pageRect.apply { set(0f, starts[i], width.toFloat(), starts[i] + heights[i]) }
            val bucket = ceil(zoom * 2).toInt().coerceIn(2, 8)
            val renderKey = key(i, bucket)
            // Keep the old image visible until a sharper one has finished rendering.
            val bitmap = detail.get(renderKey) ?: (8 downTo 3).firstNotNullOfOrNull { detail.get(key(i, it)) } ?: base.get(key(i, 2))
            if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, paint)
            // At high magnification, render the viewport rather than enlarging a
            // capped full-page bitmap. Existing images stay visible while it loads.
            tiles.get(i)?.let { canvas.drawBitmap(it.bitmap, null, it.rect, paint) }
            if (base.get(key(i, 2)) == null) render(doc, i, 2)
            if (bucket > 2 && detail.get(renderKey) == null && !scale.isInProgress && scroller.isFinished) render(doc, i, bucket)
            if (zoom > 4f && !touching && !scale.isInProgress && scroller.isFinished) renderTile(doc, i, rect)
        }
        canvas.restore()
        if (current != reportedPage) { reportedPage = current; onPageChanged?.invoke(current + 1, starts.size) }
    }

    private fun key(index: Int, bucket: Int) = (index.toLong() shl 32) or bucket.toLong()

    private fun renderTile(doc: Document, index: Int, page: RectF) {
        val visible = RectF(max(0f, offsetX / zoom), max(page.top, offsetY / zoom),
            min(width.toFloat(), (offsetX + width) / zoom), min(page.bottom, (offsetY + height) / zoom))
        if (visible.isEmpty) return
        val existing = tiles.get(index)
        if (existing != null && existing.requestedZoom >= zoom * .98f && existing.rect.contains(visible)) return
        if (!pendingTiles.add(index)) return
        val extra = 128f / zoom
        val region = RectF(max(0f, visible.left - extra), max(page.top, visible.top - extra),
            min(width.toFloat(), visible.right + extra), min(page.bottom, visible.bottom + extra))
        val density = zoom * min(1f, sqrt(4_000_000f / (region.width() * zoom * region.height() * zoom)))
        val requestedZoom = zoom
        val pageTop = page.top; val viewportWidth = width; val ticket = generation
        worker.execute {
            if (ticket != generation) return@execute
            var bitmap: Bitmap? = null
            try {
                val output = Bitmap.createBitmap(ceil(region.width() * density).toInt().coerceAtLeast(1),
                    ceil(region.height() * density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap = output; output.eraseColor(Color.WHITE)
                doc.renderer.openPage(index).use { pdfPage ->
                    val transform = Matrix().apply {
                        setScale(viewportWidth * density / pdfPage.width, viewportWidth * density / pdfPage.width)
                        postTranslate(-region.left * density, -(region.top - pageTop) * density)
                    }
                    pdfPage.render(output, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                main.post {
                    if (ticket == generation && !disposed) {
                        pendingTiles.remove(index)
                        if (width == viewportWidth) tiles.put(index, Tile(region, requestedZoom, output)) else output.recycle()
                        invalidate()
                    } else output.recycle()
                }
            } catch (_: Exception) {
                bitmap?.recycle()
                main.post { if (ticket == generation) pendingTiles.remove(index) }
            } catch (_: OutOfMemoryError) {
                bitmap?.recycle()
                main.post { if (ticket == generation) { pendingTiles.remove(index); tiles.evictAll() } }
            }
        }
    }

    private fun render(doc: Document, index: Int, bucket: Int) {
        val renderKey = key(index, bucket)
        if (!pending.add(renderKey)) return
        val ticket = generation
        val viewportWidth = width
        val desired = (viewportWidth * bucket / 2).coerceIn(1, 2600)
        worker.execute {
            if (ticket != generation) return@execute
            try {
                val bitmap = renderBitmap(doc, index, desired)
                main.post {
                    if (ticket == generation && !disposed) {
                        pending.remove(renderKey)
                        if (width == viewportWidth) (if (bucket == 2) base else detail).put(renderKey, bitmap) else bitmap.recycle()
                        invalidate()
                    } else bitmap.recycle()
                }
            } catch (e: Exception) {
                main.post { if (ticket == generation && !disposed) { pending.remove(renderKey); onFailure?.invoke("页面渲染失败：" + e.message) } }
            } catch (_: OutOfMemoryError) {
                main.post { if (ticket == generation && !disposed) { pending.remove(renderKey); detail.evictAll(); onFailure?.invoke("内存不足，无法显示此页面") } }
            }
        }
    }

    private fun renderBitmap(doc: Document, index: Int, desired: Int): Bitmap = doc.renderer.openPage(index).use { page ->
        val aspect = page.height.toDouble() / page.width.coerceAtLeast(1)
        val w = min(desired, sqrt(4_000_000.0 / aspect).toInt()).coerceAtLeast(1)
        val h = (w * aspect).roundToInt().coerceAtLeast(1)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    fun thumbnail(index: Int, width: Int, result: (Bitmap?) -> Unit) {
        val doc = document ?: return
        val ticket = generation
        worker.execute {
            if (ticket != generation) return@execute
            val bitmap = try { renderBitmap(doc, index, width.coerceIn(1, 480)) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            main.post { if (ticket == generation && !disposed) result(bitmap) else bitmap?.recycle() }
        }
    }

    private fun clampOffsets() {
        offsetX = offsetX.coerceIn(minPanX(), maxPanX())
        offsetY = offsetY.coerceIn(0f, max(0f, baseHeight * zoom - height))
    }

    private fun minPanX() = if (marginMode) -textPadding else 0f
    private fun maxPanX() = max(0f, width * zoom - width) + if (marginMode) textPadding else 0f

    private fun zoomTo(value: Float, focusX: Float, focusY: Float) {
        if (isSizeLocked) return
        cancelTextFit()
        val fittedZoom = document?.textEdges?.let { textFitZoom(it) } ?: 1f
        val next = value.coerceIn(if (marginMode) min(1f, fittedZoom) else 1f, max(4f, fittedZoom)); val ratio = next / zoom
        offsetX = (offsetX + focusX) * ratio - focusX; offsetY = (offsetY + focusY) * ratio - focusY
        zoom = next; isTextFitted = false
        if (next == 1f) marginMode = false
        clampOffsets(); onViewportChanged?.invoke(); invalidate()
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector) = !isSizeLocked
        override fun onScale(detector: ScaleGestureDetector): Boolean { zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY); return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { postInvalidateDelayed(100) }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scale.isInProgress) {
                if (!isSizeLocked && dx != 0f) {
                    val old = offsetX; offsetX = (offsetX + dx).coerceIn(minPanX(), maxPanX())
                    if (old != offsetX) { cancelTextFit(); isTextFitted = false; onViewportChanged?.invoke() }
                }
                offsetY += dy; clampOffsets(); invalidate()
            }; return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(offsetX.roundToInt(), offsetY.roundToInt(), if (isSizeLocked) 0 else -vx.toInt(), -vy.toInt(),
                minPanX().toInt(), maxPanX().toInt(), 0, max(0, (baseHeight * zoom - height).toInt()))
            postInvalidateOnAnimation(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomTo(if (zoom > 1.2f) 1f else 2f, e.x, e.y); return true }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            if (!isSizeLocked) offsetX = scroller.currX.toFloat()
            offsetY = scroller.currY.toFloat(); postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touching = false
        if (!isSizeLocked) scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) { performClick(); postInvalidateDelayed(120) }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
