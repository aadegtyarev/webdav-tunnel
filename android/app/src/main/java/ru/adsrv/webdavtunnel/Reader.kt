package ru.adsrv.webdavtunnel

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Turns a raw HTML page into a clean, navigable "Clean" document: text, headings,
 * lists, blockquotes and **links** (made absolute) are kept; scripts, styles, media
 * and forms are dropped. Large images either render inline ([images] = true) or
 * become click-to-load placeholders; small images (icons/logos/sprites/avatars/
 * pixels) are removed. A light reflow pass collapses short wrapper boxes onto one
 * line, spaces out run-together inline links, and drops emptied elements.
 *
 * No JavaScript is executed — single fetch + parse. JS-rendered pages are handled by
 * the browser's DOM fallback instead.
 */
object Reader {

    data class Result(val title: String, val bodyHtml: String, val ok: Boolean)

    private val iconHint = Regex("icon|logo|sprite|avatar|emoji|favicon|badge|spacer|pixel", RegexOption.IGNORE_CASE)
    private val blockTags = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd",
        "blockquote", "pre", "hr", "table", "thead", "tbody", "tr", "td", "th", "figure"
    )
    private val inlineTags = setOf(
        "a", "b", "i", "em", "strong", "code", "u", "s", "mark", "sub", "sup", "cite", "q",
        "span", "font", "small", "time", "abbr", "label"
    )

    fun fromHtml(rawHtml: String, baseUrl: String, images: Boolean, lazy: Boolean): Result {
        val doc = Jsoup.parse(rawHtml, baseUrl)
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: doc.title()
        val body = doc.body() ?: return Result(title.trim(), "", false)

        body.select(
            "script,style,noscript,template,iframe,svg,canvas,video,audio,object,embed," +
                "form,button,input,select,textarea,source,[aria-hidden=true],[hidden]"
        ).remove()

        // large images → inline <img> or click-to-load placeholder; icons dropped
        for (img in body.select("img")) {
            val src = img.absUrl("src").ifBlank {
                img.absUrl("data-src").ifBlank { img.absUrl("data-original") }
            }
            if (src.isBlank() || isIcon(img, src)) { img.remove(); continue }
            val alt = img.attr("alt").trim()
            val el = when {
                !images -> // click-to-load placeholder
                    doc.createElement("a").addClass("imgph").attr("data-src", src)
                        .text(if (alt.isBlank()) "image" else "image: $alt")
                lazy ->    // styled placeholder; injected JS auto-loads it on scroll
                    doc.createElement("a").addClass("imgph").addClass("lazy").attr("data-src", src)
                        .text(if (alt.isBlank()) "image" else "image: $alt")
                else ->    // load immediately (all images at once)
                    doc.createElement("img").addClass("rimg").attr("src", src)
                        .also { if (alt.isNotBlank()) it.attr("alt", alt) }
            }
            img.replaceWith(el)
        }

        // make links absolute, strip all other attributes (keep href / placeholder data-src)
        for (el in body.allElements) {
            if (el.hasClass("imgph") || el.hasClass("rimg") || el.hasClass("lazy")) continue
            val id = el.id()   // keep anchor targets so in-page #fragment links work
            if (el.tagName() == "a") {
                val raw = el.attr("href").trim()
                // pure #fragment stays relative → WebView scrolls in-page; otherwise absolutize
                val href = if (raw.startsWith("#")) raw else el.absUrl("href")
                el.clearAttributes()
                if (href.isNotBlank()) el.attr("href", href)
            } else {
                el.clearAttributes()
            }
            if (id.isNotBlank()) el.attr("id", id)
        }

        // collapse short, block-free wrapper boxes onto one line (e.g. article meta/stats)
        for (el in body.select("div,section,header,footer,figure")) {
            val hasBlockChild = el.children().any { it.tagName() in blockTags || it.tagName() == "div" || it.tagName() == "section" }
            if (!hasBlockChild && el.text().length <= 80) el.addClass("inl")
        }

        // ensure whitespace around inline elements so links don't fuse with neighbouring
        // text/links ("ai-talent49 минут назад", "AI Talent Hub@ai-talent", "назад641")
        for (el in body.allElements.toList()) {
            val inline = el.tagName() in inlineTags || el.hasClass("inl") || el.hasClass("imgph")
            if (!inline) continue
            when (val next = el.nextSibling()) {
                is Element -> if (next.tagName() !in blockTags) el.after(TextNode(" "))
                is TextNode -> { val t = next.wholeText; if (t.isNotEmpty() && t[0].isLetterOrDigit()) el.after(TextNode(" ")) }
                else -> {}
            }
            (el.previousSibling() as? TextNode)?.let { p ->
                val t = p.wholeText
                if (t.isNotEmpty() && t.last().isLetterOrDigit()) el.before(TextNode(" "))
            }
        }

        // drop elements emptied by the cleanup (e.g. list bullets that held only images)
        repeat(3) {
            body.select("p,li,ul,ol,div,section,a,blockquote,h1,h2,h3,h4,h5,h6").forEach {
                if (!it.hasClass("imgph") && it.text().isBlank() && it.select("img,a.imgph").isEmpty()) it.remove()
            }
        }

        return Result(title.trim(), body.html(), body.text().trim().length >= 60)
    }

    private fun isIcon(img: Element, src: String): Boolean {
        if (src.startsWith("data:")) return true
        val w = img.attr("width").toIntOrNull()
        val h = img.attr("height").toIntOrNull()
        if (w != null && w < 150) return true
        if (h != null && h < 150) return true
        val hint = "${img.className()} ${img.id()} ${img.attr("alt")} $src"
        return iconHint.containsMatchIn(hint)
    }
}
