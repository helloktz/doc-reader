package io.github.helloktz.docreader

import android.graphics.*
import android.graphics.drawable.Drawable

enum class Icon { BACK, EXPORT, FIT, LOCK, UNLOCK, OUTLINE, PAGES, CLOSE }

/** Small, original line icons; labels are accessibility metadata, not visible captions. */
class ReaderIcon(private val icon: Icon, color: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg xy: Float) {
            val path = Path(); path.moveTo(xy[0], xy[1])
            for (i in 2 until xy.size step 2) path.lineTo(xy[i], xy[i + 1])
            canvas.drawPath(path, pen)
        }
        when (icon) {
            Icon.BACK -> { line(14f, 5f, 7f, 12f, 14f, 19f); line(7f, 12f, 21f, 12f) }
            Icon.EXPORT -> { line(12f, 3f, 12f, 15f); line(7f, 10f, 12f, 15f, 17f, 10f); line(4f, 15f, 4f, 21f, 20f, 21f, 20f, 15f) }
            Icon.FIT -> { line(3f, 4f, 3f, 20f); line(21f, 4f, 21f, 20f); line(5f, 12f, 19f, 12f); line(8f, 9f, 5f, 12f, 8f, 15f); line(16f, 9f, 19f, 12f, 16f, 15f) }
            Icon.LOCK, Icon.UNLOCK -> {
                canvas.drawRoundRect(5f, 10f, 19f, 21f, 2f, 2f, pen)
                if (icon == Icon.LOCK) canvas.drawArc(8f, 2f, 16f, 14f, 180f, 180f, false, pen)
                else canvas.drawArc(8f, 2f, 18f, 14f, 180f, 160f, false, pen)
                line(12f, 14f, 12f, 17f)
            }
            Icon.OUTLINE -> { for (y in listOf(5f, 12f, 19f)) { canvas.drawCircle(3f, y, .6f, pen); line(7f, y, 21f, y) } }
            Icon.PAGES -> { for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) canvas.drawRoundRect(x, y, x + 7, y + 8, 1f, 1f, pen) }
            Icon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { pen.colorFilter = filter }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
