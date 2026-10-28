package io.github.manamiproject.modb.core.extractor

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.test.Test

internal class LabelledLinksTest {

    @Nested
    inner class HtmlTerminatingChildTests {

        private val html = """
            <html><body>
              <div class="entry"><a href="/anime/1">One</a><span class="type">Sequel</span></div>
              <div class="entry"><a href="/anime/2">Two</a></div>
            </body></html>
        """.trimIndent()

        @Test
        fun `html() returns the html of each element using the css selector based extractor`() {
            runTest {
                // when
                val result = JsoupCssSelectorDataExtractor.extract(html, mapOf(
                    "entries" to "//div[@class='entry']/html()",
                ))

                // then
                val entries = result.listNotNull<String>("entries")
                assertThat(entries).hasSize(2)
                assertThat(entries.first()).contains("href=\"/anime/1\"", "Sequel")
                assertThat(entries.last()).contains("href=\"/anime/2\"").doesNotContain("Sequel")
            }
        }

        @Test
        fun `html() returns the html of each element using the xpath based extractor`() {
            runTest {
                // when
                val result = JsoupXPathDataExtractor.extract(html, mapOf(
                    "entries" to "//div[@class='entry']/html()",
                ))

                // then
                val entries = result.listNotNull<String>("entries")
                assertThat(entries).hasSize(2)
                assertThat(entries.first()).contains("href=\"/anime/1\"", "Sequel")
                assertThat(entries.last()).contains("href=\"/anime/2\"").doesNotContain("Sequel")
            }
        }
    }

    @Nested
    inner class ExtractLabelledLinksTests {

        @Test
        fun `extracts label and links per snippet`() {
            runTest {
                // given
                val snippets = listOf(
                    """<div><a href="/anime/1">One</a><a href="/anime/2">Two</a><span class="type">Side Story</span></div>""",
                    """<div><a href="/anime/3">Three</a><span class="type">Sequel</span></div>""",
                )

                // when
                val result = XmlDataExtractor.extractLabelledLinks(
                    snippets = snippets,
                    labelSelector = "//span[@class='type']/text()",
                    linkSelector = "//a/@href",
                )

                // then
                assertThat(result).containsExactly(
                    LabelledLinks("Side Story", listOf("/anime/1", "/anime/2")),
                    LabelledLinks("Sequel", listOf("/anime/3")),
                )
            }
        }

        @Test
        fun `label is null if the snippet doesn't contain one`() {
            runTest {
                // when
                val result = XmlDataExtractor.extractLabelledLinks(
                    snippets = listOf("""<div><a href="/anime/1">One</a></div>"""),
                    labelSelector = "//span[@class='type']/text()",
                    linkSelector = "//a/@href",
                )

                // then
                assertThat(result).containsExactly(
                    LabelledLinks(null, listOf("/anime/1")),
                )
            }
        }

        @Test
        fun `snippets without links are skipped`() {
            runTest {
                // when
                val result = XmlDataExtractor.extractLabelledLinks(
                    snippets = listOf("""<div><span class="type">Sequel</span></div>"""),
                    labelSelector = "//span[@class='type']/text()",
                    linkSelector = "//a/@href",
                )

                // then
                assertThat(result).isEmpty()
            }
        }

        @Test
        fun `table rows are parsed correctly if they are wrapped`() {
            runTest {
                // given
                val row = """<tr><td class="label">Side Story</td><td><a href="/anime/1">One</a></td></tr>"""

                // when
                val result = XmlDataExtractor.extractLabelledLinks(
                    snippets = listOf(row),
                    labelSelector = "//td[@class='label']/text()",
                    linkSelector = "//a/@href",
                    wrap = WRAP_TABLE_ROW,
                )

                // then
                assertThat(result).containsExactly(
                    LabelledLinks("Side Story", listOf("/anime/1")),
                )
            }
        }
    }
}
