package io.github.devweiqi.duplicateimages

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs

private const val SAMPLE = 32
const val MAX_IMAGES = 2000
private const val MAX_BYTES = 32 * 1024 * 1024

/** Images are compared in their original orientation; transparent RGB is ignored. */
data class ImageEntry(
    val path: Path,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val fileHash: String,
    val pixelHash: String,
    val preview: BufferedImage,
    val samples: FloatArray,
    val shape: Long,
    val texture: Long,
    val colors: List<Int>,
    val monochrome: Boolean,
    val visible: Boolean,
) {
    val densityVariant = densityVariant(path)
    val dimensions: String = "$width × $height"
    val colorText: String = colors.joinToString(" / ") { hexColor(it) }
}

data class DensityVariant(val root: Path, val name: String, val folder: String, val qualifiers: List<String>, val density: String)

private fun densityVariant(path: Path): DensityVariant? {
    val folder = path.parent ?: return null
    val root = folder.parent ?: return null
    if (root.fileName?.toString() !in setOf("res", "composeResources")) return null
    val parts = folder.fileName.toString().split('-')
    if (parts.first() !in setOf("drawable", "mipmap")) return null
    val densities = setOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    val qualifiers = parts.drop(1)
    val density = qualifiers.firstOrNull { it in densities || it.matches(Regex("[0-9]+dpi")) }
    return DensityVariant(root, path.fileName.toString().substringBeforeLast('.'), parts.first(), qualifiers.filter { it != density }, density ?: "default")
}

fun isDensityVariant(a: ImageEntry, b: ImageEntry): Boolean {
    val first = a.densityVariant ?: return false
    val second = b.densityVariant ?: return false
    return first.root == second.root &&
        first.name == second.name &&
        first.folder == second.folder &&
        first.qualifiers == second.qualifiers &&
        first.density != second.density
}

fun hexColor(rgb: Int): String = "#%06X".format(rgb and 0xffffff)

data class ImageMatch(
    val first: ImageEntry,
    val second: ImageEntry,
    val exact: Boolean,
    val resized: Boolean,
    val tinted: Boolean,
) {
    val description: String
        get() =
            when {
                exact -> "Identical file"
                resized && tinted -> "Dimensions + Tint · Suggested"
                resized -> "Different dimensions · Suggested"
                tinted -> "Different Tint · Suggested"
                first.pixelHash == second.pixelHash -> "Identical pixels"
                else -> "Similar pixels · Suggested"
            }
    val key: String = listOf(first.path.toString(), second.path.toString()).sorted().joinToString("\u0000") + "\u0000" + description
}

// Keep ambiguous same-density files separate (for example icon.png and icon.webp).
private fun resourceFamilies(images: List<ImageEntry>): List<List<ImageEntry>> =
    images.groupBy { it.densityVariant?.copy(density = "") ?: it.path }.values.flatMap { entries ->
        if (entries.map { it.densityVariant?.density }.distinct().size == entries.size) listOf(entries) else entries.map { listOf(it) }
    }

data class ImageGroup(
    val files: List<ImageEntry>,
    val matches: List<ImageMatch>,
) {
    val variants = resourceFamilies(files).associate { it.first().path to it }
    val images = variants.values.map { it.first() }
    private val representatives = variants.flatMap { (path, entries) -> entries.map { it.path to path } }.toMap()
    val matchesByPair = matches.groupBy { setOf(representatives.getValue(it.first.path), representatives.getValue(it.second.path)) }
        .mapValues { (_, pairs) -> pairs.minBy { (if (it.exact) 0 else 1) + (if (it.resized) 2 else 0) + (if (it.tinted) 2 else 0) } }
}

data class ComparisonResult(
    val groups: List<ImageGroup>,
    val limited: Boolean,
)

fun readImage(path: Path): ImageEntry {
    Files.newInputStream(path).use { stream ->
        val bytes = stream.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw IOException("Image exceeds 32 MiB")
        if (bytes.size >= 21 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 8, Charsets.US_ASCII) == "WEBPVP8X" &&
            bytes[20].toInt() and 2 != 0
        ) {
            throw IOException("Animated WebP is not supported")
        }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input == null) throw IOException("Cannot open image")
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) throw IOException("No image decoder")
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) throw IOException("Image exceeds 16 million pixels")
                val image = reader.read(0) ?: throw IOException("Cannot decode image")
                try {
                    return describeImage(path, bytes, image)
                } finally {
                    image.flush()
                }
            } finally {
                reader.dispose()
            }
        }
    }
}

fun describeImage(
    path: Path,
    bytes: ByteArray,
    image: BufferedImage,
): ImageEntry {
    val digest = MessageDigest.getInstance("SHA-256")
    val fileHash = digest.digest(bytes).joinToString("") { "%02x".format(it) }
    digest.update(
        ByteBuffer
            .allocate(8)
            .putInt(image.width)
            .putInt(image.height)
            .array(),
    )
    val row = IntArray(image.width)
    val buffer = ByteBuffer.allocate(image.width * 4)
    for (y in 0 until image.height) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        image.getRGB(0, y, image.width, 1, row, 0, image.width)
        buffer.clear()
        row.forEach { buffer.putInt(if (it ushr 24 == 0) 0 else it) }
        digest.update(buffer.array())
    }
    val pixelHash = digest.digest().joinToString("") { "%02x".format(it) }
    val normalized = resized(image, SAMPLE, SAMPLE)
    val samples = FloatArray(SAMPLE * SAMPLE * 4)
    val palette = mutableMapOf<Int, Int>()
    for (y in 0 until SAMPLE) {
        for (x in 0 until SAMPLE) {
            val pixel = normalized.getRGB(x, y)
            val alpha = (pixel ushr 24) / 255f
            val i = (y * SAMPLE + x) * 4
            samples[i] = alpha
            for (channel in 0..2) samples[i + channel + 1] = ((pixel ushr (16 - channel * 8)) and 255) / 255f
            if (alpha >= 0.5f) palette.merge(pixel and 0xffffff, 1, Int::plus)
        }
    }
    normalized.flush()
    val ranked = palette.entries.sortedByDescending { it.value }.map { it.key }
    val dominant = ranked.firstOrNull() ?: 0

    fun colorDistance(
        a: Int,
        b: Int,
    ): Int = listOf(16, 8, 0).maxOf { abs(((a ushr it) and 255) - ((b ushr it) and 255)) }
    val solidPixels = palette.values.sum()
    val monochrome = solidPixels > 0 && palette.filterKeys { colorDistance(it, dominant) <= 16 }.values.sum() >= solidPixels * 0.98
    val colors = mutableListOf<Int>()
    for (color in ranked) {
        if (colors.none { colorDistance(it, color) <= 24 }) colors.add(color)
        if (colors.size == 3 || monochrome) break
    }
    var shape = 0L
    for (y in 0..7) {
        for (x in 0..7) {
            var alpha = 0f
            for (dy in 0..3) for (dx in 0..3) alpha += samples[((y * 4 + dy) * SAMPLE + x * 4 + dx) * 4]
            if (alpha >= 8) shape = shape or (1L shl (y * 8 + x))
        }
    }
    var texture = 0L
    for (y in 0..7) {
        for (x in 0..7) {
            val left = ((y * 4 + 2) * SAMPLE + x * 4) * 4
            val right = left + 12

            fun light(i: Int): Float = (samples[i + 1] * 0.299f + samples[i + 2] * 0.587f + samples[i + 3] * 0.114f) * samples[i]
            if (light(left) > light(right) + 0.005f) texture = texture or (1L shl (y * 8 + x))
        }
    }
    val scale = minOf(1.0, 96.0 / maxOf(image.width, image.height))
    return ImageEntry(
        path,
        bytes.size.toLong(),
        image.width,
        image.height,
        fileHash,
        pixelHash,
        resized(image, maxOf(1, (image.width * scale).toInt()), maxOf(1, (image.height * scale).toInt())),
        samples,
        shape,
        texture,
        colors,
        monochrome,
        samples.indices.step(4).any { samples[it] > 0.05f },
    )
}

private fun resized(
    image: BufferedImage,
    width: Int,
    height: Int,
): BufferedImage {
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(image, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return target
}

fun compareImages(
    a: ImageEntry,
    b: ImageEntry,
): ImageMatch? {
    if (a.fileHash == b.fileHash) return ImageMatch(a, b, exact = true, resized = false, tinted = false)
    if (a.pixelHash == b.pixelHash) return ImageMatch(a, b, exact = false, resized = false, tinted = false)
    val aspectRatio = (a.width.toDouble() / a.height) / (b.width.toDouble() / b.height)
    if (!a.visible || !b.visible || abs(1 - aspectRatio) > 0.02 || java.lang.Long.bitCount(a.shape xor b.shape) > 8) return null
    if (!(a.monochrome && b.monochrome) && java.lang.Long.bitCount(a.texture xor b.texture) > 8) return null
    val localAlphaError = DoubleArray(64)
    val localCoverage = DoubleArray(64)
    var alphaError = 0.0
    var colorError = 0.0
    var coverage = 0.0
    for (i in a.samples.indices step 4) {
        val aa = a.samples[i]
        val ba = b.samples[i]
        val pixel = i / 4
        val tile = pixel / SAMPLE / 4 * 8 + pixel % SAMPLE / 4
        localAlphaError[tile] += abs(aa - ba)
        localCoverage[tile] += maxOf(aa, ba)
        alphaError += abs(aa - ba)
        coverage += maxOf(aa, ba)
        for (channel in 1..3) {
            val delta = aa * a.samples[i + channel] - ba * b.samples[i + channel]
            colorError += delta * delta
        }
    }
    if (coverage < 1 || alphaError / coverage > 0.065) return null
    // Shared backgrounds must not hide different internal cutouts, such as ! and ?.
    if (localCoverage.indices.any { localCoverage[it] >= 4 && localAlphaError[it] / localCoverage[it] > 0.25 }) return null
    val sameColor = colorError / (coverage * 3) <= 0.0025
    // Tint-invariant matching is deliberately limited to monochrome artwork.
    if (!sameColor && !(a.monochrome && b.monochrome)) return null
    return ImageMatch(a, b, exact = false, resized = a.width != b.width || a.height != b.height, tinted = !sameColor)
}

data class MatchOptions(
    val dimensions: Boolean = false,
    val tint: Boolean = false,
) {
    fun accepts(match: ImageMatch): Boolean =
        when {
            match.exact || match.first.pixelHash == match.second.pixelHash -> true
            !match.resized && !match.tinted -> false
            else -> (!match.resized || dimensions) && (!match.tinted || tint)
        }
}

fun compareAll(
    images: List<ImageEntry>,
    options: MatchOptions = MatchOptions(),
    progress: (Long, Long) -> Unit = { _, _ -> },
    cancelled: () -> Unit = {},
): ComparisonResult {
    val parents = IntArray(images.size) { it }

    fun root(index: Int): Int {
        var node = index
        while (parents[node] != node) {
            parents[node] = parents[parents[node]]
            node = parents[node]
        }
        return node
    }
    val index = images.withIndex().associate { it.value.path to it.index }
    resourceFamilies(images).forEach { family ->
        val representative = index.getValue(family.first().path)
        family.drop(1).forEach { parents[index.getValue(it.path)] = representative }
    }
    val matches = mutableListOf<ImageMatch>()
    var limited = false
    val total = images.size.toLong() * (images.size - 1) / 2
    var completed = 0L
    progress(0, total)
    outer@ for (i in images.indices) {
        cancelled()
        progress(completed, total)
        for (j in i + 1 until images.size) {
            if (j % 64 == 0) cancelled()
            completed++
            if (isDensityVariant(images[i], images[j])) continue
            if (!options.dimensions && !options.tint && images[i].fileHash != images[j].fileHash && images[i].pixelHash != images[j].pixelHash) continue
            val match = compareImages(images[i], images[j]) ?: continue
            if (!options.accepts(match)) continue
            if (matches.size >= 20_000) {
                limited = true
                break@outer
            }
            matches.add(match)
            parents[root(j)] = root(i)
        }
    }
    progress(completed, total)
    val byRoot = images.indices.groupBy { root(it) }
    val edges = matches.groupBy { root(index.getValue(it.first.path)) }
    val groups =
        byRoot
            .filterKeys { it in edges }
            .map { (key, indices) ->
                ImageGroup(indices.map { images[it] }, edges[key].orEmpty())
            }.sortedBy {
                it.images
                    .first()
                    .path
                    .toString()
            }
    return ComparisonResult(groups, limited)
}
