package io.github.helloktz.docreader

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Keeps 48dp actions intact and wraps controls and page metadata when needed. */
@android.annotation.SuppressLint("ViewConstructor")
class ReaderBar(context: Context, private val actions: List<View>, private val page: View) : ViewGroup(context) {
    private fun dp(n: Int) = ReaderUi.dp(context, n)
    private var stacked = false
    private var columns = actions.size
    private var rows = 1
    init {
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(context.getColor(R.color.reader_surface))
        actions.forEach { addView(it) }; addView(page)
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = max(0, width - paddingLeft - paddingRight)
        val button = MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY)
        actions.forEach { it.measure(button, button) }
        page.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        columns = ((available + dp(8)) / dp(56)).coerceIn(1, actions.size)
        rows = (actions.size + columns - 1) / columns
        val lastCount = actions.size - (rows - 1) * columns
        val actionWidth = lastCount * dp(48) + (lastCount - 1) * dp(8)
        stacked = actionWidth + dp(16) + page.measuredWidth > available
        val height = dp((rows - 1) * 56) + if (stacked) dp(48 + 8) + page.measuredHeight else max(dp(48), page.measuredHeight)
        setMeasuredDimension(width, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        actions.forEachIndexed { i, child ->
            val column = i % columns
            val x = if (rtl) width - paddingRight - dp(48) - column * dp(56) else paddingLeft + column * dp(56)
            val y = paddingTop + (i / columns) * dp(56)
            child.layout(x, y, x + dp(48), y + dp(48))
        }
        val x = if (rtl) paddingLeft else width - paddingRight - page.measuredWidth
        val y = paddingTop + dp((rows - 1) * 56) + if (stacked) dp(56) else (max(dp(48), page.measuredHeight) - page.measuredHeight) / 2
        page.layout(x, y, x + page.measuredWidth, y + page.measuredHeight)
    }
}
