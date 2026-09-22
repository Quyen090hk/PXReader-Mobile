package io.github.quyen090hk.pxreader.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Matches the WebView bridge's UTF-16 text-node offsets instead of Jsoup's collapsed .text(). */
internal fun epubIndexedText(html: String): String = buildString {
    fun appendText(node: Node) {
        if (node is TextNode) append(node.wholeText)
        node.childNodes().forEach(::appendText)
    }
    appendText(Jsoup.parse(html).body())
}
