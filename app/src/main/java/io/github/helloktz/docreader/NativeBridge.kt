package io.github.helloktz.docreader

object NativeBridge {
    init { System.loadLibrary("doc_reader") }
    external fun convert(input: String, output: String): String?
}
