package io.github.helloktz.docreader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.ImageButton

/** IconPark vectors are adapted locally; no icon font or network dependency. */
enum class Icon(val resource: Int) {
    OPEN(R.drawable.ic_open), BACK(R.drawable.ic_back), EXPORT(R.drawable.ic_export),
    RESET(R.drawable.ic_reset), FIT(R.drawable.ic_fit), LOCK(R.drawable.ic_lock), UNLOCK(R.drawable.ic_unlock),
    OUTLINE(R.drawable.ic_outline), PAGES(R.drawable.ic_pages), CLOSE(R.drawable.ic_close)
}

object ReaderUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun shape(context: Context, color: Int, radius: Int = 14, stroke: Int? = null, strokeWidth: Int = 1) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(context, radius).toFloat()
            if (stroke != null) setStroke(dp(context, strokeWidth), stroke)
        }

    fun background(context: Context, emphasized: Boolean = false, outlined: Boolean = false, radius: Int = 14): RippleDrawable {
        val accent = context.getColor(R.color.reader_accent)
        val soft = context.getColor(R.color.reader_accent_soft)
        val resting = if (emphasized) soft else if (outlined) context.getColor(R.color.reader_surface) else Color.TRANSPARENT
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(context, soft, radius, accent, 2))
            addState(intArrayOf(android.R.attr.state_selected), shape(context, soft, radius, accent))
            addState(intArrayOf(), shape(context, resting, radius, if (outlined) context.getColor(R.color.reader_border) else null))
        }
        return RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.reader_ripple)), states, shape(context, Color.WHITE, radius))
    }

    fun icon(context: Context, icon: Icon, name: String, emphasized: Boolean = false, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon.resource)
        setPadding(dp(context, 11), dp(context, 11), dp(context, 11), dp(context, 11))
        minimumWidth = dp(context, 48); minimumHeight = dp(context, 48)
        background = background(context, emphasized)
        contentDescription = name; tooltipText = name
        setOnClickListener { action() }
    }
}
