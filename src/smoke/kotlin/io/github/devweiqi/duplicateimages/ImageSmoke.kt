package io.github.devweiqi.duplicateimages

import com.android.tools.adtui.webp.WebpMetadata
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

fun main() {
    val code = try {
        runChecks()
        0
    } catch (e: Throwable) {
        e.printStackTrace()
        1
    }
    // The isolated mock IDE starts platform threads outside the test disposable.
    kotlin.system.exitProcess(code)
}

private fun runChecks() {
    val directory = Files.createTempDirectory("duplicate-images-check")
    try {
        fun entry(
            name: String,
            size: Int = 96,
            color: Int = 0xe5bb65,
            circle: Boolean = false,
        ): ImageEntry {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().let { g ->
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(color)
                if (circle) {
                    g.fillOval(size / 8, size / 8, size * 3 / 4, size * 3 / 4)
                } else {
                    val xs = intArrayOf(48, 60, 88, 68, 73, 48, 23, 28, 8, 36).map { it * size / 96 }.toIntArray()
                    val ys = intArrayOf(8, 33, 37, 57, 85, 72, 85, 57, 37, 33).map { it * size / 96 }.toIntArray()
                    g.fillPolygon(xs, ys, xs.size)
                }
                g.dispose()
            }
            val file = directory.resolve(name)
            ImageIO.write(image, "png", file.toFile())
            return readImage(file)
        }
        System.getenv("CHECK_RESOURCE_ROOT")?.let { resourceRoot ->
            WebpMetadata.ensureWebpRegistered()
            val originals = Files.walk(Path.of(resourceRoot)).use { paths ->
                paths.filter { it.fileName.toString() in setOf("ic_danger.webp", "ic_question_circle.webp") }.map(::readImage).toList()
            }
            check(originals.size == 10)
            val found = compareAll(originals, MatchOptions(true, true))
            println("Original symbol images: ${found.groups.size} groups, ${found.groups.sumOf { it.matches.size }} false matches")
            check(found.groups.isEmpty()) { "Different punctuation symbols must not match across any densities" }
        }

        fun symbol(name: String, question: Boolean, size: Int, color: Int = 0x1a1616): ImageEntry {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().apply {
                scale(size / 48.0, size / 48.0)
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                this.color = Color(color)
                fillOval(2, 2, 44, 44)
                composite = java.awt.AlphaComposite.Clear
                fillRect(22, 32, 4, 4)
                if (question) {
                    fillRect(20, 14, 8, 3)
                    fillRect(25, 17, 3, 6)
                    fillRect(22, 21, 6, 3)
                    fillRect(22, 24, 3, 5)
                } else {
                    fillRect(22, 14, 3, 15)
                }
                dispose()
            }
            return describeImage(directory.resolve(name), name.toByteArray(), image)
        }
        val warning = symbol("warning.png", false, 48)
        val question = symbol("question.png", true, 72)
        check(compareAll(listOf(warning, question), MatchOptions(true, true)).groups.isEmpty()) { "A shared circular background cannot hide different internal symbols" }
        check(compareImages(warning, symbol("warning-large.png", false, 96)) != null)
        check(compareImages(warning, symbol("warning-tint.png", false, 48, 0x88aafa))?.tinted == true)
        val a = entry("a.png")
        val b = entry("b.png", size = 192)
        val c = entry("c.png", color = 0x88aafa)
        val d = entry("d.png")
        val different = entry("circle.png", circle = true)
        val ab = checkNotNull(compareImages(a, b))
        val ac = checkNotNull(compareImages(a, c))
        val bc = checkNotNull(compareImages(b, c))
        check(ab.resized && !ab.tinted)
        check(ac.tinted && !ac.resized)
        check(bc.resized && bc.tinted)
        check(compareImages(a, d)?.exact == true)
        check(compareImages(a, different) == null) { "A circle cannot match a star" }
        check(a.colorText == "#E5BB65") { a.colorText }
        check(c.colorText == "#88AAFA")
        val all = listOf(a, b, c, d, different)
        check(
            compareAll(all)
                .groups
                .single()
                .images
                .map { it.path.fileName.toString() }
                .toSet() == setOf("a.png", "d.png"),
        )
        val dimensions = compareAll(all, MatchOptions(dimensions = true)).groups.single()
        check(dimensions.images.size == 3 && c !in dimensions.images)
        val tint = compareAll(all, MatchOptions(tint = true)).groups.single()
        check(tint.images.size == 3 && b !in tint.images)
        val both = compareAll(all, MatchOptions(true, true)).groups.single()
        check(both.images.size == 4 && both.matches.size == 6)
        check(!MatchOptions(dimensions = true).accepts(bc))
        check(!MatchOptions(tint = true).accepts(bc))
        check(MatchOptions(true, true).accepts(bc))
        val resourceRoot = directory.resolve("shared/src/commonMain/composeResources")
        val hdpi = a.copy(path = resourceRoot.resolve("drawable-hdpi/icon.png"))
        val xhdpi = b.copy(path = resourceRoot.resolve("drawable-xhdpi/icon.png"))
        check(isDensityVariant(hdpi, xhdpi))
        check(compareAll(listOf(hdpi, xhdpi), MatchOptions(true, true)).groups.isEmpty())
        check(compareAll(listOf(hdpi, xhdpi.copy(fileHash = hdpi.fileHash, pixelHash = hdpi.pixelHash))).groups.isEmpty())
        check(!isDensityVariant(hdpi, xhdpi.copy(path = resourceRoot.resolve("drawable-xhdpi/other.png"))))
        check(!isDensityVariant(hdpi, xhdpi.copy(path = directory.resolve("feature/src/commonMain/composeResources/drawable-xhdpi/icon.png"))))
        check(!isDensityVariant(hdpi, xhdpi.copy(path = resourceRoot.resolve("drawable-night-xhdpi/icon.png"))))
        check(!isDensityVariant(hdpi, xhdpi.copy(path = resourceRoot.resolve("mipmap-xhdpi/icon.png"))))
        check(isDensityVariant(hdpi, xhdpi.copy(path = resourceRoot.resolve("drawable/icon.png"))))
        check(isDensityVariant(hdpi.copy(path = directory.resolve("app/src/main/res/drawable-hdpi/icon.png")), xhdpi.copy(path = directory.resolve("app/src/main/res/drawable-480dpi/icon.webp"))))
        check(!isDensityVariant(a.copy(path = directory.resolve("photos/icon.png")), b.copy(path = directory.resolve("photos/large/icon.png"))))
        val dpiCopies = listOf(hdpi, xhdpi, hdpi.copy(path = resourceRoot.resolve("drawable-hdpi/copy.png")), xhdpi.copy(path = resourceRoot.resolve("drawable-xhdpi/copy.png")))
        for (options in listOf(MatchOptions(), MatchOptions(true, true))) {
            val familyGroup = compareAll(dpiCopies, options).groups.single()
            check(familyGroup.images.size == 2) { "Density siblings must occupy one card per resource, including indirect matches" }
            check(familyGroup.files.size == 4 && familyGroup.variants.values.all { it.size == 2 })
            check(familyGroup.matchesByPair.values.single().exact)
            check(familyGroup.matches.none { isDensityVariant(it.first, it.second) })
        }
        val progressValues = mutableListOf<Pair<Long, Long>>()
        compareAll(all, MatchOptions(true, true), progress = { done, total -> progressValues.add(done to total) })
        check(progressValues.first() == 0L to 10L && progressValues.last() == 10L to 10L)
        check(progressValues.zipWithNext().all { (a, b) -> a.first <= b.first })
        check(ScanProgress("Compare", 1, 4).percent == 25)
        check(ScanProgress("Find", 5).percent == null)
        check(ScanProgress("Empty", 0, 0).percent == 100)
        println("Density variants excluded only within the same resource family; comparison progress is monotonic: OK")
        checkPanel(both)
        println("Exact copies, size, Tint, combined differences, HEX colors, negatives and all four filter combinations: OK")

        val hidden = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        hidden.setRGB(0, 0, 0x00ff0000)
        val first = describeImage(directory.resolve("hidden-a.png"), byteArrayOf(1), hidden)
        hidden.setRGB(0, 0, 0x000000ff)
        val second = describeImage(directory.resolve("hidden-b.png"), byteArrayOf(2), hidden)
        check(compareImages(first, second)?.description == "Identical pixels")
        val wide = entry("wide.png").copy(width = 192)
        check(compareImages(a, wide.copy(fileHash = "different", pixelHash = "different")) == null)
        var interrupted = false
        try {
            compareAll(all) { throw InterruptedException() }
        } catch (_: InterruptedException) {
            interrupted = true
        }
        check(interrupted)

        fun striped(
            name: String,
            size: Int,
            shift: Int,
        ): ImageEntry {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    image.setRGB(
                        x,
                        y,
                        if (x <
                            size / 2
                        ) {
                            Color(80 + shift, 20 + shift, 20 + shift).rgb
                        } else {
                            Color(20 + shift, 20 + shift, 80 + shift).rgb
                        },
                    )
                }
            }
            return describeImage(directory.resolve(name), name.toByteArray(), image)
        }
        val chain = listOf(striped("x", 64, 0), striped("y", 96, 10), striped("z", 128, 20))
        val chainGroup = compareAll(chain, MatchOptions(true, true)).groups.single()
        check(chainGroup.images.size == 3 && chainGroup.matches.size == 2)
        check(compareImages(chain[0], chain[2]) == null) { "Do not infer a direct match from group membership" }
        println("Transparency, aspect ratio, cancellation and non-transitive pair relationships: OK")

        fun fails(path: Path) {
            try {
                readImage(path)
                error("Malformed image accepted: $path")
            } catch (_: IOException) {
            }
        }
        fails(Files.write(directory.resolve("bad.png"), byteArrayOf(1, 2, 3)))
        fails(Files.write(directory.resolve("large.png"), ByteArray(32 * 1024 * 1024 + 1)))
        val animation = ByteArray(30)
        "RIFF".toByteArray().copyInto(animation)
        "WEBPVP8X".toByteArray().copyInto(animation, 8)
        animation[20] = 2
        fails(Files.write(directory.resolve("animated.webp"), animation))
        WebpMetadata.ensureWebpRegistered()
        val webp = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ/Y/+ByKi/wEA")
        val decoded = readImage(Files.write(directory.resolve("static.webp"), webp))
        check(decoded.width == 2 && decoded.height == 2)
        val jpeg = BufferedImage(40, 24, BufferedImage.TYPE_INT_RGB)
        jpeg.createGraphics().apply {
            color = Color(0x88aafa)
            fillRect(0, 0, 40, 24)
            dispose()
        }
        val out = ByteArrayOutputStream()
        check(ImageIO.write(jpeg, "jpeg", out))
        check(readImage(Files.write(directory.resolve("photo.jpg"), out.toByteArray())).dimensions == "40 × 24")
        check(isImagePath(Path.of("UPPER.PNG")))
        check(!isImagePath(Path.of("vector.xml")))
        check(isScanPath(directory.resolve("shared/src/commonMain/composeResources/drawable/image.png"), listOf(directory)))
        check(!isScanPath(directory.resolve("build/image.png"), listOf(directory)))
        check(!isResourceImagePath(directory.resolve("iosApp/Assets.xcassets/AppIcon.appiconset/icon.png")))
        check(!isResourceImagePath(directory.resolve("docs/preview.png")))
        check(!isResourceImagePath(directory.resolve("shared/src/commonMain/kotlin/icon.png")))
        check(isResourceImagePath(directory.resolve("shared/src/commonMain/composeResources/drawable/icon.webp")))
        check(isResourceImagePath(directory.resolve("app/src/debug/res/drawable/icon.png")))
        check(!isScanPath(directory.resolve(".git/image.png"), listOf(directory)))
        check(!isScanPath(directory.resolveSibling("elsewhere/image.png"), listOf(directory)))
        println("Real PNG/JPEG/WebP decoding, malformed/oversized/animated files and scan path exclusions: OK")
    } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

private fun checkPanel(group: ImageGroup) {
    val lifetime = com.intellij.openapi.util.Disposer.newDisposable()
    val environment = com.intellij.core.CoreApplicationEnvironment(lifetime)
    val project = com.intellij.mock.MockProject(environment.application.picoContainer, lifetime)
    val properties = MemoryProperties()
    project.registerService(com.intellij.ide.util.PropertiesComponent::class.java, properties)
    environment.application.registerService(com.intellij.ide.util.PropertiesComponent::class.java, properties)
    try {
        javax.swing.SwingUtilities.invokeAndWait {
            com.intellij.ui.IconManager.activate(com.intellij.ui.icons.CoreIconManager())
            val service = ImageScanService(project)
            checkExecutorCancellation(service)
            com.intellij.openapi.util.Disposer.register(lifetime, service)
            ImageScanService::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
                .set(service, ScanSnapshot(listOf(group), group.images.size, message = "Smoke check"))
            val panel = ImagePanel(project, service)
            try {
                fun components(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap {
                    listOf(it) + if (it is java.awt.Container) components(it) else emptyList()
                }
                val checks = components(panel).filterIsInstance<javax.swing.JCheckBox>()
                check(!checks.single { it.text == "Include different dimensions" }.isSelected)
                check(!checks.single { it.text == "Include different Tint" }.isSelected)
                check(checks.single { it.text == "Auto-check changes" }.isSelected)
                val table = components(panel).filterIsInstance<javax.swing.JTable>().single()
                check(table.rowCount == 6)
                check((0 until table.rowCount).any { table.getValueAt(it, 2).toString().contains("#E5BB65 → #88AAFA") })
                table.setRowSelectionInterval(0, 0)
                check(!components(panel).filterIsInstance<javax.swing.JButton>().single { it.text == "Open file" }.isEnabled)
                panel.search.text = "not-a-real-image"
                check(table.rowCount == 0)
                panel.search.text = ""
                check(table.rowCount == 6)
                panel.setSize(1280, 740)

                fun layout(container: java.awt.Container) {
                    container.doLayout()
                    container.components.filterIsInstance<java.awt.Container>().forEach(::layout)
                }
                repeat(4) { layout(panel) }
                check(checks.all { it.width > 0 && it.height > 0 })
                val image = BufferedImage(1280, 740, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().apply {
                    panel.printAll(this)
                    dispose()
                }
                Files.createDirectories(Path.of("build"))
                ImageIO.write(image, "png", Path.of("build/panel-smoke.png").toFile())
                val goldPixels = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) and 0xffffff == 0xe5bb65 } }
                check(goldPixels > 500) { "Image cards rendered blank: only $goldPixels gold pixels" }
                val largeImages = List(64) { group.images.first().copy(path = Path.of("/sample/image$it.png")) }
                val large = compareAll(largeImages).groups.single()
                val snapshotField = ImageScanService::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
                var updates = 0
                table.model.addTableModelListener { updates++ }
                snapshotField.set(service, ScanSnapshot(listOf(large), 64))
                val started = System.nanoTime()
                service.listeners.forEach { it() }
                println("Large group: ${table.rowCount} rows, $updates model events, ${(System.nanoTime() - started) / 1_000_000} ms on EDT")
                check(updates <= 2) { "Large group emits $updates table events instead of a batch update" }
                updates = 0
                snapshotField.set(service, service.snapshot.copy(scanning = true, message = "Checking…"))
                service.listeners.forEach { it() }
                check(updates == 0) { "Starting a background scan rebuilt the old results on EDT" }
                snapshotField.set(service, service.snapshot.copy(progress = ScanProgress("Comparing pairs", 20, 100)))
                service.listeners.forEach { it() }
                val progress = components(panel).filterIsInstance<javax.swing.JProgressBar>().single()
                check(progress.isVisible && !progress.isIndeterminate && progress.value == 20)
                val cancel = components(panel).filterIsInstance<javax.swing.JButton>().single { it.text == "Cancel" }
                check(cancel.isVisible)
                cancel.doClick(0)
                check(!service.snapshot.scanning && !progress.isVisible && !cancel.isVisible)
                check(table.rowCount == 2016 && updates == 0) { "Cancel must preserve previous results" }
                val root = group.images.first().path.parent
                val resourceDirectory = Files.createDirectories(root.resolve("shared/src/commonMain/composeResources/drawable"))
                ImageScanService::class.java.getDeclaredField("roots").apply { isAccessible = true }.set(service, listOf(root))
                service.setAutoCheck(false)
                val source = checkNotNull(environment.localFileSystem.findFileByPath(group.images.first().path.toString()))
                val destination = resourceDirectory.resolve("new-copy.png")
                val destinationParent = checkNotNull(environment.localFileSystem.findFileByPath(resourceDirectory.toString()))
                Files.copy(group.images.first().path, destination)
                environment.application.messageBus.syncPublisher(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES).after(
                    listOf(com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent(null, source, destinationParent, "new-copy.png"))
                )
                val changed = ImageScanService::class.java.getDeclaredField("invalidated").apply { isAccessible = true }.get(service) as Set<*>
                check(destination in changed) { "Copy event was not delivered to the project listener" }
                val debounce = ImageScanService::class.java.getDeclaredField("timer").apply { isAccessible = true }.get(service) as javax.swing.Timer
                check(!debounce.isRunning) { "Auto-check off should not schedule a scan" }
                ImageScanService::class.java.getDeclaredField("autoCheck").apply { isAccessible = true }.set(service, true)
                environment.application.messageBus.syncPublisher(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES).after(
                    listOf(com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent(null, source, destinationParent, "new-copy.png"))
                )
                check(debounce.isRunning) { "A copied image must schedule auto-check" }
                service.cancelScan()

                val newImage = readImage(destination)
                val old = compareAll(group.images)
                val copied = compareAll(group.images + newImage)
                val newMatches = newlyMatchedPairs(copied.groups, old.groups.flatMap { it.matches }.mapTo(mutableSetOf()) { it.key })
                check(newMatches.isNotEmpty() && newMatches.all { it.first.path == destination || it.second.path == destination })
                check(newlyMatchedPairs(copied.groups, copied.groups.flatMap { it.matches }.mapTo(mutableSetOf()) { it.key }).isEmpty())
                println("Real VFS copy event reaches listener; copied file creates new notification matches without repeat alerts: OK")
                val resultList = components(panel).filterIsInstance<javax.swing.JList<*>>().single { it.accessibleContext.accessibleName == "Matching image groups" }
                var listUpdates = 0
                resultList.model.addListDataListener(object : javax.swing.event.ListDataListener {
                    override fun intervalAdded(e: javax.swing.event.ListDataEvent) {
                        listUpdates++
                    }

                    override fun intervalRemoved(e: javax.swing.event.ListDataEvent) {
                        listUpdates++
                    }

                    override fun contentsChanged(e: javax.swing.event.ListDataEvent) {
                        listUpdates++
                    }
                })
                val manyGroups = List(500) { i ->
                    val a = group.images.first().copy(path = Path.of("/sample/$i/a.png"))
                    val b = a.copy(path = Path.of("/sample/$i/b.png"))
                    ImageGroup(listOf(a, b), listOf(ImageMatch(a, b, true, false, false)))
                }
                snapshotField.set(service, ScanSnapshot(manyGroups, 1000))
                service.listeners.forEach { it() }
                check(resultList.model.size == 500 && listUpdates <= 2) { "Group list was updated $listUpdates times" }
                check(resultList.fixedCellHeight > 0 && resultList.fixedCellWidth > 0)
                println("500 groups are published with $listUpdates list events and fixed row metrics: OK")
                val densityRoot = Path.of("/sample/shared/src/commonMain/composeResources")
                val small = group.images.first().copy(path = densityRoot.resolve("drawable-hdpi/icon.png"))
                val largeVariant = group.images[1].copy(path = densityRoot.resolve("drawable-xhdpi/icon.png"))
                // Only the non-representative variant matches the other resource.
                val copyVariant = largeVariant.copy(path = densityRoot.resolve("drawable-xhdpi/copy.png"))
                val densityGroup = compareAll(listOf(small, largeVariant, copyVariant)).groups.single()
                snapshotField.set(service, ScanSnapshot(listOf(densityGroup), 3))
                service.listeners.forEach { it() }
                val cards = components(panel).filterIsInstance<javax.swing.JList<*>>().single { it !== resultList }
                check(cards.model.size == 2 && table.rowCount == 1)
                check(table.getValueAt(0, 1).toString().contains("xhdpi: ${largeVariant.dimensions}"))
                check(table.getValueAt(0, 3) == "Identical file")
                val selector = components(panel).filterIsInstance<javax.swing.JComboBox<*>>().single()
                check(selector.isVisible && selector.itemCount == 2)
                selector.selectedIndex = 1
                val selectedPath = components(panel).filterIsInstance<javax.swing.JTextField>().single { it.accessibleContext.accessibleName == "Selected image full path" }
                check(selectedPath.text == largeVariant.path.toString())
                panel.search.text = "drawable-xhdpi/icon"
                check(resultList.model.size == 1) { "Search must include hidden density variants" }
                panel.setSize(1280, 740)
                repeat(4) { layout(panel) }
                val densityPreview = BufferedImage(1280, 740, BufferedImage.TYPE_INT_ARGB)
                densityPreview.createGraphics().apply {
                    panel.printAll(this)
                    dispose()
                }
                ImageIO.write(densityPreview, "png", Path.of("build/density-panel-smoke.png").toFile())
                println("Density families: two cards, actual matched dimensions, variant file selection and search: OK")
                if (System.getenv("RENDER_PREVIEW") == "1") renderPreview(project, group)
            } finally {
                panel.dispose()
            }
        }
        println("Native Swing panel: default checkboxes, HEX pair table, pair selection, file actions and search: OK")
    } finally {
        com.intellij.openapi.util.Disposer.dispose(lifetime)
    }
}

private class MemoryProperties : com.intellij.ide.util.PropertiesComponent() {
    private val values = mutableMapOf<String, String>()

    override fun unsetValue(name: String) {
        values.remove(name)
    }

    override fun isValueSet(name: String): Boolean = name in values

    override fun getValue(name: String): String? = values[name]

    override fun setValue(name: String, value: String?) {
        if (value == null) unsetValue(name) else values[name] = value
    }

    override fun setValue(name: String, value: String?, defaultValue: String?) {
        setValue(name, value.takeUnless { it == defaultValue })
    }

    override fun setValue(name: String, value: Float, defaultValue: Float) {
        setValue(name, value.toString(), defaultValue.toString())
    }

    override fun setValue(name: String, value: Int, defaultValue: Int) {
        setValue(name, value.toString(), defaultValue.toString())
    }

    override fun setValue(name: String, value: Boolean, defaultValue: Boolean) {
        setValue(name, value.toString(), defaultValue.toString())
    }

    override fun getValues(name: String): Array<String>? = values[name]?.split("\n")?.toTypedArray()

    override fun setValues(name: String, values: Array<out String>?) {
        setValue(name, values?.joinToString("\n"))
    }

    override fun getList(name: String): List<String>? = values[name]?.split("\n")

    override fun setList(name: String, values: MutableCollection<String>?) {
        setValue(name, values?.joinToString("\n"))
    }

    override fun updateValue(name: String, value: Boolean): Boolean {
        val changed = getBoolean(name) != value
        setValue(name, value)
        return changed
    }
}

private fun checkExecutorCancellation(service: ImageScanService) {
    val executor = ImageScanService::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(service) as java.util.concurrent.ExecutorService
    val started = java.util.concurrent.CountDownLatch(1)
    val finish = java.util.concurrent.CountDownLatch(1)
    try {
        val first = executor.submit {
            started.countDown()
            try {
                finish.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        check(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val next = executor.submit<Boolean> { Thread.currentThread().isInterrupted }
        ImageScanService::class.java.getDeclaredField("task").apply { isAccessible = true }.set(service, first)
        service.cancelScan()
        finish.countDown()
        val interrupted = next.get(2, java.util.concurrent.TimeUnit.SECONDS)
        println("Queued scan inherits cancelled worker interrupt: $interrupted")
        check(!interrupted) { "Cancellation prevents the next queued scan from running" }
    } finally {
        finish.countDown()
    }
}

private fun renderPreview(project: com.intellij.openapi.project.Project, source: ImageGroup) {
    // Render the real panel with a dark palette without starting the full IDE.
    val defaults = javax.swing.UIManager.getDefaults()
    defaults.keys().toList().forEach { key ->
        if (defaults[key] is Color) {
            val name = key.toString().lowercase()
            val rgb = when {
                "selectionbackground" in name -> 0x34476a
                "foreground" in name || "text" in name && "background" !in name -> 0xdfe1e5
                "background" in name -> 0x2b2d30
                "shadow" in name -> 0x202124
                else -> 0x43454a
            }
            defaults[key] = javax.swing.plaf.ColorUIResource(rgb)
        }
    }
    listOf("Button.gradient", "CheckBox.gradient", "ScrollBar.gradient").forEach { defaults[it] = listOf(0f, 0f, Color(0x393b40), Color(0x393b40), Color(0x393b40)) }
    com.intellij.ui.JBColor.setDark(true)
    val service = ImageScanService(project)
    ImageScanService::class.java.getDeclaredField("autoCheck").apply { isAccessible = true }.set(service, true)
    ImageScanService::class.java.getDeclaredField("options").apply { isAccessible = true }.set(service, MatchOptions(true, true))
    val root = Path.of("/sample/shared/src/commonMain/composeResources")
    val entries = listOf(
        source.images[0].copy(path = root.resolve("drawable-hdpi/ic_favorite.png")),
        source.images[1].copy(path = root.resolve("drawable-xhdpi/ic_favorite.png")),
        source.images[3].copy(path = Path.of("/sample/profile/src/commonMain/composeResources/drawable/ic_star.png")),
        source.images[1].copy(path = root.resolve("drawable/illustration_star.png")),
        source.images[2].copy(path = root.resolve("drawable/ic_star_accent.png")),
    )
    val groups = compareAll(entries, MatchOptions(true, true)).groups
    ImageScanService::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        .set(service, ScanSnapshot(groups, entries.size, message = "5 images checked · 1 matching group · No files modified"))
    val panel = ImagePanel(project, service)
    try {
        val frame = javax.swing.JPanel(java.awt.BorderLayout()).apply {
            add(
                javax.swing.JLabel("Duplicate Image Finder").apply {
                    border = javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14)
                    font = font.deriveFont(java.awt.Font.BOLD)
                },
                java.awt.BorderLayout.NORTH
            )
            add(panel, java.awt.BorderLayout.CENTER)
            setSize(1600, 760)
        }

        fun layout(container: java.awt.Container) {
            container.doLayout()
            container.components.filterIsInstance<java.awt.Container>().forEach(::layout)
        }
        repeat(4) { layout(frame) }
        val image = BufferedImage(frame.width * 2, frame.height * 2, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            scale(2.0, 2.0)
            frame.printAll(this)
            dispose()
        }
        Files.createDirectories(Path.of("docs"))
        ImageIO.write(image, "png", Path.of("docs/preview.png").toFile())
    } finally {
        panel.dispose()
        service.dispose()
    }
}
