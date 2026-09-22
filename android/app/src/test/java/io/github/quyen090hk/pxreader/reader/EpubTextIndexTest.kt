package io.github.quyen090hk.pxreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubTextIndexTest {
    @Test
    fun keepsWhitespaceAndUtf16OffsetsLikeDomTextNodes() {
        val indexed = epubIndexedText("<html><head><title>ignored</title></head><body><p>甲 <strong>😀乙</strong></p><p>丙&nbsp;丁</p></body></html>")

        assertEquals("甲 😀乙丙\u00a0丁", indexed)
        assertEquals(2, indexed.indexOf("😀"))
        assertEquals(4, indexed.indexOf("乙"))
    }

    @Test
    fun preservesTextAcrossInlineElementsWithoutInventingSpaces() {
        assertEquals("前缀正文后缀", epubIndexedText("<body><p>前缀<b>正文</b>后缀</p></body>"))
    }
}
