package io.github.helloktz.docreader

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

/** Lazy page rendering: bitmaps exist only for the visible pages and a bounded cache. */
class PdfCanvas(context: Context) : View(context) {
    private data class PageSize(val width: Int, val height: Int)
    private class Document(val renderer: PdfRenderer, val sizes: List<PageSize>)
    private val worker = Executors.newSingleThreadExecutor()
    // Completion and cleanup must also run when this view is detached from its Activity.
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private var document: Document? = null
    private val pending = mutableSetOf<Long>()
    private var generation = 0
    private var disposed = false
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var starts = FloatArray(0)
    private var heights = FloatArray(0)
    private var baseHeight = 0f
    private val gap = 12f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pageRect = RectF()
    var onPageChanged: ((Int, Int) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    private var reportedPage = -1

    fun open(file: File, ready: (Int) -> Unit) {
        closeDocument()
        val ticket = generation
        worker.execute {
            var renderer: PdfRenderer? = null
            try {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
                val sizes = (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { PageSize(it.width, it.height) }
                }
                require(sizes.isNotEmpty()) { "PDF 没有可显示的页面" }
                val loaded = Document(renderer, sizes)
                main.post {
                    if (ticket != generation || disposed) { worker.execute { loaded.renderer.close() } }
                    else {
                        document = loaded
                        rebuildLayout()
                        ready(sizes.size)
                        invalidate()
                    }
                }
            } catch (e: Exception) {
                renderer?.close()
                main.post { if (ticket == generation && !disposed) onFailure?.invoke(
                    if (e is SecurityException) "此 PDF 需要密码，当前版本暂不支持" else "无法打开 PDF：${e.message ?: "文件可能已损坏"}"
                ) }
            }
        }
    }

    fun closeDocument() {
        generation++
        val old = document
        document = null
        if (old != null) worker.execute { old.renderer.close() }
        cache.evictAll()
        pending.clear()
        zoom = 1f; offsetX = 0f; offsetY = 0f
        starts = FloatArray(0); heights = FloatArray(0); baseHeight = 0f
        reportedPage = -1
        invalidate()
    }

    fun dispose() {
        disposed = true
        closeDocument()
        // A loading document posts its close back to this worker; keep the executor alive
        // until that post is delivered, then let queued renderer work finish.
        worker.execute { main.post { worker.shutdown() } }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fraction = if (baseHeight > 0) offsetY / (baseHeight * zoom) else 0f
        cache.evictAll()
        rebuildLayout()
        offsetY = fraction * baseHeight * zoom
        clampOffsets()
    }

    private fun rebuildLayout() {
        val sizes = document?.sizes ?: return
        val pageWidth = max(1f, width - gap * 2)
        starts = FloatArray(sizes.size)
        heights = FloatArray(sizes.size)
        var y = gap
        sizes.forEachIndexed { i, size ->
            starts[i] = y
            heights[i] = pageWidth * size.height / size.width.coerceAtLeast(1)
            y += heights[i] + gap
        }
        baseHeight = y
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(226, 229, 225))
        val doc = document ?: return
        if (width <= 0) return
        canvas.save()
        canvas.translate(-offsetX, -offsetY)
        canvas.scale(zoom, zoom)
        val top = offsetY / zoom
        val bottom = (offsetY + height) / zoom
        var current = 0
        for (i in starts.indices) {
            if (starts[i] <= top + height / zoom / 3) current = i
            if (starts[i] + heights[i] < top || starts[i] > bottom) continue
            val rect = pageRect.apply { set(gap, starts[i], width - gap, starts[i] + heights[i]) }
            paint.color = Color.WHITE
            canvas.drawRect(rect, paint)
            val bucket = ceil(zoom * 2).toInt().coerceIn(2, 8)
            val key = (i.toLong() shl 32) or bucket.toLong()
            val bitmap = cache.get(key)
            if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, paint)
            else {
                paint.color = Color.GRAY; paint.textSize = 13 * resources.displayMetrics.scaledDensity
                canvas.drawText("第 ${i + 1} 页", rect.left + gap, rect.top + gap * 2, paint)
                render(doc, i, bucket, key)
            }
        }
        canvas.restore()
        if (current != reportedPage) { reportedPage = current; onPageChanged?.invoke(current + 1, starts.size) }
    }

    private fun render(doc: Document, index: Int, bucket: Int, key: Long) {
        if (!pending.add(key)) return
        val ticket = generation
        val desired = ((width - gap * 2) * bucket / 2).toInt().coerceIn(1, 2400)
        worker.execute {
            try {
                val bitmap = doc.renderer.openPage(index).use { page ->
                    val aspect = page.height.toDouble() / page.width.coerceAtLeast(1)
                    val w = min(desired, sqrt(4_000_000.0 / aspect).toInt()).coerceAtLeast(1)
                    val h = (w * aspect).roundToInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                main.post {
                    if (ticket == generation && !disposed) {
                        pending.remove(key); cache.put(key, bitmap); invalidate()
                    } else bitmap.recycle()
                }
            } catch (e: Exception) {
                main.post { if (ticket == generation && !disposed) {
                    pending.remove(key); onFailure?.invoke("页面渲染失败：${e.message}")
                } }
            } catch (_: OutOfMemoryError) {
                main.post { if (ticket == generation && !disposed) {
                    pending.remove(key); cache.evictAll(); onFailure?.invoke("内存不足，无法显示此页面")
                } }
            }
        }
    }

    private fun clampOffsets() {
        offsetX = offsetX.coerceIn(0f, max(0f, width * zoom - width))
        offsetY = offsetY.coerceIn(0f, max(0f, baseHeight * zoom - height))
    }

    private fun zoomTo(value: Float, focusX: Float, focusY: Float) {
        val next = value.coerceIn(1f, 4f)
        val ratio = next / zoom
        offsetX = (offsetX + focusX) * ratio - focusX
        offsetY = (offsetY + focusY) * ratio - focusY
        zoom = next; clampOffsets(); invalidate()
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scale.isInProgress) {
                offsetX += distanceX; offsetY += distanceY; clampOffsets(); invalidate()
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (zoom > 1.2f) 1f else 2f, e.x, e.y)
            return true
        }
    })
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
