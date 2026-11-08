package io.github.helloktz.docreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.*
import android.widget.*

@android.annotation.SuppressLint("ViewConstructor") // Constructed in code with its active document, never inflated from XML.
class NavigationPanel(context: Context, private val pdf: PdfCanvas, private val dismiss: () -> Unit) : FrameLayout(context) {
    var mode = 0; private set
    private var closed = false
    private val green = Color.rgb(36, 92, 76)
    private val body = FrameLayout(context)
    private val tabs = mutableListOf<ImageButton>()
    private val thumbs = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableSetOf<Int>()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    init {
        setBackgroundColor(0x55000000)
        setOnClickListener { dismiss() }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat()
            isClickable = true
        }
        val width = minOf((resources.displayMetrics.widthPixels * .84f).toInt(), dp(360))
        val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun tab(icon: Icon, name: String, action: () -> Unit): ImageButton = ImageButton(context).apply {
            setImageDrawable(ReaderIcon(icon, green)); setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.TRANSPARENT); contentDescription = name; tooltipText = name
            setOnClickListener { action() }
        }
        tabs += tab(Icon.OUTLINE, "大纲") { select(0) }
        tabs += tab(Icon.PAGES, "页面预览") { select(1) }
        tabs.forEach { bar.addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
        bar.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(tab(Icon.CLOSE, "关闭") { dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(bar)
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(panel, LayoutParams(width, -1, Gravity.START))
    }

    fun select(value: Int) {
        mode = value
        tabs.forEachIndexed { i, tab -> tab.setBackgroundColor(if (i == mode) 0x18245c4c else Color.TRANSPARENT) }
        body.removeAllViews()
        if (value == 0) showOutline() else showPages()
    }

    fun refreshOutline() { if (mode == 0) select(0) }
    fun close() { closed = true; thumbs.evictAll() }

    private fun showOutline() {
        val entries = pdf.outlines
        if (entries.isEmpty()) {
            body.addView(TextView(context).apply { text = "无大纲"; gravity = Gravity.CENTER; setTextColor(Color.GRAY) }, LayoutParams(-1, -1))
            return
        }
        val active = entries.indexOfLast { it.page <= pdf.currentPage }
        val list = ListView(context).apply { dividerHeight = 0 }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val entry = entries[position]
                return (recycled as? TextView ?: TextView(context)).apply {
                    text = entry.title; textSize = 15f; minHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL; setTextColor(green)
                    setPadding(dp(16 + entry.level.coerceAtMost(6) * 12), dp(12), dp(12), dp(12))
                    setBackgroundColor(if (position == active) 0x18245c4c else Color.TRANSPARENT)
                }
            }
        }
        list.setOnItemClickListener { _, _, position, _ -> pdf.jumpToPage(entries[position].page); dismiss() }
        body.addView(list, LayoutParams(-1, -1)); if (active >= 0) list.setSelection(active)
    }

    private fun showPages() {
        val grid = GridView(context).apply {
            numColumns = 2; verticalSpacing = dp(8); horizontalSpacing = dp(8)
            setPadding(dp(8), dp(8), dp(8), dp(8)); clipToPadding = false
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = pdf.pageCount
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val cell = recycled as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4))
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(-1, dp(170)))
                    addView(TextView(context).apply { gravity = Gravity.CENTER; textSize = 12f; setTextColor(green) }, LinearLayout.LayoutParams(-1, dp(28)))
                }
                val image = cell.getChildAt(0) as ImageView
                image.contentDescription = "第 ${position + 1} 页"
                image.setImageBitmap(thumbs.get(position))
                (cell.getChildAt(1) as TextView).text = context.getString(R.string.page_number, position + 1)
                cell.background = GradientDrawable().apply {
                    setColor(0xfff5f5f2.toInt()); setStroke(dp(1), if (position == pdf.currentPage) green else 0xffddddda.toInt())
                }
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
