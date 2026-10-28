package io.github.manamiproject.modb.core.extractor

/**
 * Links which belong together under a common label. Used to read related anime together with the kind of relation.
 * @since 20.0.0
 * @property label The label as it appears in the raw content. `null` if there is none.
 * @property links Raw values of the links (usually `href` attributes) in the order of the raw content.
 */
public data class LabelledLinks(
    val label: String?,
    val links: List<String>,
)

/**
 * Extracts a label and the links belonging to it from each of the given HTML snippets.
 * The snippets are usually the result of a selector ending with `html()`, one snippet per entry or group of entries.
 * @since 20.0.0
 * @param snippets HTML snippets. Each one is processed on its own.
 * @param labelSelector XPath selecting the label within a snippet. If it matches multiple times, the first match is used.
 * @param linkSelector XPath selecting the links within a snippet.
 * @param wrap Transforms a snippet before it is parsed. Table rows for example must be wrapped in `<table>` to be parsed correctly.
 * @return One [LabelledLinks] per snippet which contains at least one link.
 */
public suspend fun DataExtractor.extractLabelledLinks(
    snippets: List<String>,
    labelSelector: Selector,
    linkSelector: Selector,
    wrap: (String) -> String = { it },
): List<LabelledLinks> {
    return snippets.mapNotNull { snippet ->
        val data = extract(wrap(snippet), mapOf(
            "label" to labelSelector,
            "links" to linkSelector,
        ))

        if (data.notFound("links")) {
            return@mapNotNull null
        }

        val label = if (data.notFound("label")) {
            null
        } else {
            data.listNotNull<String>("label").firstOrNull()
        }

        LabelledLinks(
            label = label,
            links = data.listNotNull<String>("links"),
        )
    }
}

/**
 * Wraps a table row (`<tr>`) in a table so that it is parsed as a row instead of being dropped by the HTML parser.
 * @since 20.0.0
 */
public val WRAP_TABLE_ROW: (String) -> String = { "<table>$it</table>" }
