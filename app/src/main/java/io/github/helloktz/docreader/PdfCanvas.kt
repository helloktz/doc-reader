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
import kotlin.math.*

data class OutlineEntry(val title: String, val page: Int, val level: Int)

/** All renderer access is serialized; zoom gestures reuse already rendered pages. */
class PdfCanvas(context: Context) : View(context) {
    private data class Size(val width: Int, val height: Int)
    private class Document(val renderer: PdfRenderer, val sizes: List<Size>)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private fun bitmapCache(bytes: Int) = object : LruCache<Long, Bitmap>(bytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val base = bitmapCache(32 * 1024 * 1024)
    private val detail = bitmapCache(48 * 1024 * 1024)
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
                val loaded = Document(renderer, sizes)
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
        generation++; scroller.forceFinished(true)
        val old = document; document = null
        if (old != null) worker.execute { old.renderer.close() }
        base.evictAll(); detail.evictAll(); pending.clear()
        zoom = 1f; offsetX = 0f; offsetY = 0f; isSizeLocked = false
        starts = FloatArray(0); heights = FloatArray(0); baseHeight = 0f
        outlines = emptyList(); reportedPage = -1; pendingPosition = null
        invalidate()
    }

    fun dispose() {
        disposed = true; closeDocument()
        worker.execute { main.post { worker.shutdown() } }
    }

    fun fitWidth() {
        if (isSizeLocked) return
        scroller.forceFinished(true)
        offsetY /= zoom; zoom = 1f; offsetX = 0f
        clampOffsets(); invalidate()
    }

    fun setSizeLocked(locked: Boolean) { isSizeLocked = locked; scroller.forceFinished(true); onLockChanged?.invoke() }

    fun jumpToPage(index: Int) {
        if (index !in starts.indices) return
        scroller.forceFinished(true); offsetY = starts[index] * zoom; clampOffsets(); invalidate()
    }

    fun savePosition(state: Bundle) {
        state.putFloat("zoom", zoom); state.putFloat("panX", offsetX / width.coerceAtLeast(1))
        state.putFloat("panY", offsetY / width.coerceAtLeast(1)); state.putBoolean("locked", isSizeLocked)
    }

    fun restorePosition(state: Bundle?) {
        if (state == null) return
        if (width <= 0 || document == null) { pendingPosition = state; return }
        zoom = state.getFloat("zoom", 1f).coerceIn(1f, 4f)
        offsetX = state.getFloat("panX") * width; offsetY = state.getFloat("panY") * width
        isSizeLocked = state.getBoolean("locked"); onLockChanged?.invoke(); clampOffsets(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val factor = if (oldw > 0) w.toFloat() / oldw else 1f
        offsetX *= factor; offsetY *= factor
        base.evictAll(); detail.evictAll(); rebuildLayout(); clampOffsets()
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
            if (base.get(key(i, 2)) == null) render(doc, i, 2)
            if (bucket > 2 && detail.get(renderKey) == null && !scale.isInProgress && scroller.isFinished) render(doc, i, bucket)
        }
        canvas.restore()
        if (current != reportedPage) { reportedPage = current; onPageChanged?.invoke(current + 1, starts.size) }
    }

    private fun key(index: Int, bucket: Int) = (index.toLong() shl 32) or bucket.toLong()

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
        offsetX = offsetX.coerceIn(0f, max(0f, width * zoom - width))
        offsetY = offsetY.coerceIn(0f, max(0f, baseHeight * zoom - height))
    }

    private fun zoomTo(value: Float, focusX: Float, focusY: Float) {
        if (isSizeLocked) return
        val next = value.coerceIn(1f, 4f); val ratio = next / zoom
        offsetX = (offsetX + focusX) * ratio - focusX; offsetY = (offsetY + focusY) * ratio - focusY
        zoom = next; clampOffsets(); invalidate()
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector) = !isSizeLocked
        override fun onScale(detector: ScaleGestureDetector): Boolean { zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY); return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { postInvalidateDelayed(100) }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scale.isInProgress) { if (!isSizeLocked) offsetX += dx; offsetY += dy; clampOffsets(); invalidate() }; return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(offsetX.roundToInt(), offsetY.roundToInt(), if (isSizeLocked) 0 else -vx.toInt(), -vy.toInt(),
                0, max(0, (width * zoom - width).toInt()), 0, max(0, (baseHeight * zoom - height).toInt()))
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
        if (!isSizeLocked) scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) { performClick(); postInvalidateDelayed(120) }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
