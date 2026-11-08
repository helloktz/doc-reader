package io.github.helloktz.docreader

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.*
import android.widget.*

@android.annotation.SuppressLint("ViewConstructor") // Constructed in code with its active document, never inflated from XML.
class NavigationPanel(context: Context, private val pdf: PdfCanvas, private val modeChanged: () -> Unit, private val dismiss: () -> Unit) : FrameLayout(context) {
    var mode = 0; private set
    private var closed = false
    private val accent = context.getColor(R.color.reader_accent)
    private val ink = context.getColor(R.color.reader_ink)
    private val muted = context.getColor(R.color.reader_muted)
    private val panel = LinearLayout(context)
    private val body = FrameLayout(context)
    private val tabs = mutableListOf<ImageButton>()
    private val thumbs = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableSetOf<Int>()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    init {
        setBackgroundColor(0x5c243847)
        setOnClickListener { dismiss() }
        panel.apply {
            orientation = LinearLayout.VERTICAL; elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.WHITE); val radius = dp(20).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
            clipToOutline = true
            isClickable = true
        }
        val width = minOf((resources.displayMetrics.widthPixels * .88f).toInt(), dp(360))
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        tabs += ReaderUi.icon(context, Icon.OUTLINE, "大纲") { select(0) }
        tabs += ReaderUi.icon(context, Icon.PAGES, "页面预览") { select(1) }
        tabs.forEach { bar.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }) }
        bar.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(ReaderUi.icon(context, Icon.CLOSE, "关闭") { dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(bar)
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(panel, LayoutParams(width, -1, Gravity.START))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) {
            panel.translationX = -dp(24).toFloat(); panel.alpha = 0f
            panel.animate().translationX(0f).alpha(1f).setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wanted = minOf((w * .88f).toInt(), dp(360))
        if (panel.layoutParams.width != wanted) panel.layoutParams = panel.layoutParams.apply { width = wanted }
    }

    fun select(value: Int) {
        mode = value
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == mode }
        body.removeAllViews()
        if (value == 0) showOutline() else showPages()
        modeChanged()
    }

    fun refreshOutline() { if (mode == 0) select(0) }
    fun close() { closed = true; panel.animate().cancel(); thumbs.evictAll() }

    private fun showOutline() {
        val entries = pdf.outlines
        if (entries.isEmpty()) {
            body.addView(TextView(context).apply { text = "无大纲"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(muted) }, LayoutParams(-1, -1))
            return
        }
        val active = entries.indexOfLast { it.page <= pdf.currentPage }
        val list = ListView(context).apply {
            dividerHeight = 0; setPadding(dp(8), dp(8), dp(8), dp(12)); clipToPadding = false
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val entry = entries[position]
                val row = recycled as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60)
                    addView(View(context).apply { background = ReaderUi.shape(context, accent, 2) },
                        LinearLayout.LayoutParams(dp(3), dp(24)).apply { marginEnd = dp(12) })
                    addView(TextView(context).apply {
                        textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(context).apply { textSize = 12f; setTextColor(muted); gravity = Gravity.END },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                }
                val selected = position == active
                row.setPadding(dp(12 + entry.level.coerceAtMost(4) * 8), dp(12), dp(12), dp(12))
                row.getChildAt(0).visibility = if (selected) View.VISIBLE else View.INVISIBLE
                (row.getChildAt(1) as TextView).apply {
                    text = entry.title; setTextColor(if (selected) accent else ink)
                    typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                }
                (row.getChildAt(2) as TextView).text = context.getString(R.string.page_number, entry.page + 1)
                row.background = ReaderUi.shape(context, if (selected) context.getColor(R.color.reader_accent_soft) else Color.TRANSPARENT, 12)
                row.isSelected = selected
                return row
            }
        }
        list.setOnItemClickListener { _, _, position, _ -> pdf.jumpToPage(entries[position].page); dismiss() }
        body.addView(list, LayoutParams(-1, -1)); if (active >= 0) list.setSelection(active)
    }

    private fun showPages() {
        val grid = GridView(context).apply {
            numColumns = 2; verticalSpacing = dp(12); horizontalSpacing = dp(12)
            setPadding(dp(12), dp(12), dp(12), dp(16)); clipToPadding = false
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = pdf.pageCount
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val cell = recycled as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4))
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(-1, dp(170)))
                    addView(TextView(context).apply {
                        gravity = Gravity.CENTER; textSize = 12f; minHeight = dp(32); setPadding(0, dp(6), 0, dp(6))
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                val image = cell.getChildAt(0) as ImageView
                image.contentDescription = "第 ${position + 1} 页"
                image.setImageBitmap(thumbs.get(position))
                val selected = position == pdf.currentPage
                (cell.getChildAt(1) as TextView).apply {
                    text = context.getString(R.string.page_number, position + 1)
                    setTextColor(if (selected) accent else muted)
                    typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                }
                cell.isSelected = selected
                cell.stateDescription = if (selected) "当前页" else null
                cell.background = ReaderUi.shape(context,
                    context.getColor(if (selected) R.color.reader_accent_soft else R.color.reader_background),
                    12, if (selected) accent else context.getColor(R.color.reader_border), if (selected) 2 else 1)
                if (thumbs.get(position) == null && pending.add(position)) pdf.thumbnail(position, dp(130)) { bitmap ->
                    pending.remove(position)
                    if (closed) bitmap?.recycle() else if (bitmap != null) { thumbs.put(position, bitmap); notifyDataSetChanged() }
                }
                return cell
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> pdf.jumpToPage(position); dismiss() }
        body.addView(grid, LayoutParams(-1, -1)); grid.setSelection(pdf.currentPage)
    }
}
