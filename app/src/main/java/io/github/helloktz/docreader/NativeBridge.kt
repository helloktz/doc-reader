package io.github.helloktz.docreader

object NativeBridge {
    init { System.loadLibrary("doc_reader") }
    external fun convert(input: String, output: String): String?
    external fun outline(path: String): String?
    // Global left/right, then each page's line count and top/bottom pairs, normalized by page width.
    external fun textBounds(path: String, cancelled: java.util.concurrent.atomic.AtomicBoolean): DoubleArray?
}
