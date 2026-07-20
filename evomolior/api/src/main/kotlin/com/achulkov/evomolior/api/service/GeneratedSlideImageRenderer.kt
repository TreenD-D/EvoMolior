package com.achulkov.evomolior.api.service

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders post-ready vertical carousel slide PNGs from final HTML slide-card markup.
 *
 * This renderer deliberately materializes text-heavy social slides locally instead of asking an
 * image model to draw readable copy. Image models are useful for illustration; deterministic
 * rendering is the reliable path for exact post copy, fixed dimensions, and repeatable exports.
 */
internal object GeneratedSlideImageRenderer {
    const val SLIDE_WIDTH = 1080
    const val SLIDE_HEIGHT = 1920

    private val postHeadingRegex = Regex("""(?is)<h2[^>]*>\s*Post\s+(\d+)\s*:?\s*(.*?)</h2>""")
    private val slideCardStartRegex = Regex("""(?is)<div\s+class=["'][^"']*\bslide-card\b[^"']*["'][^>]*>""")
    private val slideNumberRegex = Regex("""(?is)<span\s+class=["'][^"']*\bslide-number\b[^"']*["'][^>]*>\s*(\d+)\s*</span>""")
    private val fieldRegex = Regex("""(?is)<h5[^>]*>(.*?)</h5>\s*<p[^>]*>(.*?)</p>""")
    private val divTokenRegex = Regex("""(?is)</?div\b[^>]*>""")

    /**
     * Renders slide PNGs under [outputDirectory] and returns HTML with a gallery section embedded.
     *
     * @param html Final HTML containing `.slide-card` blocks.
     * @param outputDirectory Directory corresponding to `outputs/` in the result bundle.
     * @return HTML plus rendered slide asset descriptors.
     */
    fun renderAndEmbed(html: String, outputDirectory: File): SlideImageRenderResult {
        val slides = extractSlides(html)
        if (slides.isEmpty()) return SlideImageRenderResult(html, emptyList())

        val assets = slides.map { slide ->
            renderSlide(slide, outputDirectory)
        }
        return SlideImageRenderResult(
            html = embedGallery(html, assets),
            assets = assets
        )
    }

    private fun extractSlides(html: String): List<SlideSpec> {
        val postHeadings = postHeadingRegex.findAll(html)
            .map { match ->
                PostHeading(
                    start = match.range.first,
                    number = match.groupValues[1].toIntOrNull() ?: 0,
                    title = cleanText(match.groupValues[2]).ifBlank { "Post ${match.groupValues[1]}" }
                )
            }
            .toList()

        return slideCardStartRegex.findAll(html).mapNotNull { cardStart ->
            val cardHtml = extractBalancedDiv(html, cardStart.range.first) ?: return@mapNotNull null
            val post = postHeadings.lastOrNull { it.start < cardStart.range.first }
            val slideNumber = slideNumberRegex.find(cardHtml)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val fields = fieldRegex.findAll(cardHtml)
                .associate { field ->
                    cleanText(field.groupValues[1]).lowercase() to cleanText(field.groupValues[2])
                }
            val exactCopy = fields.entries.firstOrNull { (label, _) ->
                "exact" in label && "copy" in label
            }?.value ?: fields.values.firstOrNull().orEmpty()
            if (exactCopy.isBlank()) return@mapNotNull null

            SlideSpec(
                postNumber = post?.number?.takeIf { it > 0 } ?: 1,
                postTitle = post?.title ?: "Carousel Post",
                slideNumber = slideNumber,
                exactCopy = exactCopy
            )
        }.toList()
    }

    private fun extractBalancedDiv(html: String, startIndex: Int): String? {
        var depth = 0
        var sawOpening = false
        divTokenRegex.findAll(html, startIndex).forEach { token ->
            val text = token.value
            if (text.startsWith("</", ignoreCase = true)) {
                depth -= 1
                if (sawOpening && depth == 0) {
                    return html.substring(startIndex, token.range.last + 1)
                }
            } else {
                depth += 1
                sawOpening = true
            }
        }
        return null
    }

    private fun renderSlide(slide: SlideSpec, outputDirectory: File): RenderedSlideImageAsset {
        val relativePath = "assets/slides/post-%02d-slide-%02d.png".format(slide.postNumber, slide.slideNumber)
        val target = File(outputDirectory, relativePath).also { it.parentFile?.mkdirs() }

        val image = BufferedImage(SLIDE_WIDTH, SLIDE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val palette = paletteFor(slide.postNumber)
            graphics.paint = GradientPaint(0f, 0f, palette.first, SLIDE_WIDTH.toFloat(), SLIDE_HEIGHT.toFloat(), palette.second)
            graphics.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT)

            graphics.color = Color(255, 255, 255, 38)
            graphics.fillOval(-180, -120, 520, 520)
            graphics.fillOval(780, 1370, 520, 520)

            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 48)
            graphics.drawString("MindVortex", 88, 130)

            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 34)
            graphics.drawString("Post ${slide.postNumber} / Slide ${slide.slideNumber}", 88, 190)

            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 74)
            drawWrappedText(
                text = slide.exactCopy,
                x = 88,
                y = 520,
                maxWidth = SLIDE_WIDTH - 176,
                lineHeight = 94,
                maxLines = 10,
                graphics = graphics
            )

            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
            graphics.color = Color(255, 255, 255, 222)
            drawWrappedText(
                text = slide.postTitle,
                x = 88,
                y = SLIDE_HEIGHT - 180,
                maxWidth = SLIDE_WIDTH - 176,
                lineHeight = 40,
                maxLines = 2,
                graphics = graphics
            )

            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
            graphics.drawString("mindvortex.app", 88, SLIDE_HEIGHT - 84)
        } finally {
            graphics.dispose()
        }

        ImageIO.write(image, "png", target)
        return RenderedSlideImageAsset(
            relativePath = relativePath,
            width = SLIDE_WIDTH,
            height = SLIDE_HEIGHT,
            sizeBytes = target.length(),
            postNumber = slide.postNumber,
            slideNumber = slide.slideNumber,
            title = slide.postTitle
        )
    }

    private fun drawWrappedText(
        text: String,
        x: Int,
        y: Int,
        maxWidth: Int,
        lineHeight: Int,
        maxLines: Int,
        graphics: java.awt.Graphics2D
    ) {
        val metrics = graphics.fontMetrics
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val lines = mutableListOf<String>()
        var current = ""
        normalized.split(' ').forEach { word ->
            val candidate = listOf(current, word).filter { it.isNotBlank() }.joinToString(" ")
            if (metrics.stringWidth(candidate) <= maxWidth || current.isBlank()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotBlank()) lines += current

        lines.take(maxLines).forEachIndexed { index, line ->
            val renderedLine = if (index == maxLines - 1 && lines.size > maxLines) {
                line.trimEnd('.', ',', ';', ':') + "..."
            } else {
                line
            }
            graphics.drawString(renderedLine, x, y + index * lineHeight)
        }
    }

    private fun embedGallery(html: String, assets: List<RenderedSlideImageAsset>): String {
        if (assets.isEmpty() || html.contains("id=\"generated-slide-assets\"")) return html
        val gallery = buildString {
            appendLine()
            appendLine("<section id=\"generated-slide-assets\" class=\"generated-slide-assets\">")
            appendLine("<h2>Generated Post-Ready Slide Images</h2>")
            appendLine("<p>These PNG files are rendered at 1080x1920 for TikTok, Instagram Reels, and vertical carousel use.</p>")
            appendLine("<div class=\"generated-slide-grid\">")
            assets.forEach { asset ->
                appendLine("<figure>")
                appendLine(
                    "<img src=\"${asset.relativePath}\" width=\"270\" height=\"480\" alt=\"Post ${asset.postNumber} slide ${asset.slideNumber} rendered vertical PNG\">"
                )
                appendLine("<figcaption>Post ${asset.postNumber} · Slide ${asset.slideNumber}</figcaption>")
                appendLine("</figure>")
            }
            appendLine("</div>")
            appendLine("</section>")
        }
        return if (Regex("(?is)</body\\s*>").containsMatchIn(html)) {
            html.replaceFirst(Regex("(?is)</body\\s*>"), "$gallery\n</body>")
        } else {
            html + gallery
        }
    }

    private fun paletteFor(postNumber: Int): Pair<Color, Color> {
        return when ((postNumber - 1).floorMod(4)) {
            0 -> Color(102, 126, 234) to Color(118, 75, 162)
            1 -> Color(245, 87, 108) to Color(240, 147, 251)
            2 -> Color(67, 206, 162) to Color(24, 90, 157)
            else -> Color(255, 154, 158) to Color(250, 208, 196)
        }
    }

    private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

    private fun cleanText(value: String): String = value
        .replace(Regex("(?is)<br\\s*/?>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s+"), "\n")
        .trim()
}

internal data class SlideImageRenderResult(
    val html: String,
    val assets: List<RenderedSlideImageAsset>
)

internal data class RenderedSlideImageAsset(
    val relativePath: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val postNumber: Int,
    val slideNumber: Int,
    val title: String
)

private data class PostHeading(
    val start: Int,
    val number: Int,
    val title: String
)

private data class SlideSpec(
    val postNumber: Int,
    val postTitle: String,
    val slideNumber: Int,
    val exactCopy: String
)
