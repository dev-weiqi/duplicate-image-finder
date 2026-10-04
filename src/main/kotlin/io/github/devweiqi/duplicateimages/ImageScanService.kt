package io.github.devweiqi.duplicateimages

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer
import kotlin.io.path.extension

const val TOOL_WINDOW = "Duplicate Image Finder"
private val EXCLUDED = setOf("build", "out", "node_modules", "Pods", "vendor", "dist", "design")
private val FORMATS = setOf("png", "jpg", "jpeg", "webp")

fun isImagePath(path: Path): Boolean = path.extension.lowercase() in FORMATS

fun isResourceImagePath(path: Path): Boolean = isImagePath(path) &&
    (0 until path.nameCount - 3).any { index ->
        path.getName(index).toString() == "src" && path.getName(index + 2).toString() in setOf("res", "composeResources")
    }

fun isScanPath(
    path: Path,
    roots: List<Path>,
): Boolean =
    roots.any { root ->
        path.startsWith(root) && root.relativize(path).none { it.toString().startsWith(".") || it.toString() in EXCLUDED || it.toString().endsWith(".xcassets") }
    }

data class ScanProgress(val phase: String, val completed: Long, val total: Long? = null) {
    val percent: Int? get() = total?.let { if (it == 0L) 100 else (completed * 100 / it).toInt().coerceIn(0, 100) }
    val message: String get() = if (total == null) "$phase · $completed images found" else "$phase · $completed / $total"
}

data class ScanSnapshot(
    val groups: List<ImageGroup> = emptyList(),
    val imageCount: Int = 0,
    val issues: List<String> = emptyList(),
    val scanning: Boolean = false,
    val message: String = "Preparing image scan…",
    val progress: ScanProgress? = null,
)

class ImageStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) project.getService(ImageScanService::class.java).scan()
        }
    }
}

@Service(Service.Level.PROJECT)
class ImageScanService(
    private val project: Project,
) : Disposable {
    private val properties = PropertiesComponent.getInstance(project)
    var options =
        MatchOptions(properties.getBoolean("duplicateImages.dimensions", false), properties.getBoolean("duplicateImages.tint", false))
        private set
    var autoCheck = properties.getBoolean("duplicateImages.auto", true)
        private set
    var snapshot = ScanSnapshot()
        private set
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    var focusPath: Path? = null
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor(TOOL_WINDOW, 1)
    private var task: Future<*>? = null

    @Volatile private var generation = 0
    private var roots = emptyList<Path>()
    private var invalidated = mutableSetOf<Path>()
    private val cache = mutableMapOf<Path, CachedImage>()
    private var previousMatches = emptySet<String>()
    private var initialized = false

    @Volatile private var disposed = false
    private val pendingProgress = AtomicReference<Pair<Int, ScanProgress>?>(null)
    private val progressTimer = Timer(150) {
        val update = pendingProgress.getAndSet(null)
        if (!disposed && snapshot.scanning && update?.first == generation) {
            snapshot = snapshot.copy(message = update.second.message, progress = update.second)
            listeners.forEach { it() }
        }
    }
    private val timer = Timer(600) { scan(automatic = true) }.apply { isRepeats = false }

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (disposed) return
                    val paths =
                        events
                            .filter { event ->
                                event.file?.isDirectory == true ||
                                    event is com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent &&
                                    event.isDirectory ||
                                    isImagePath(Path.of(event.path)) ||
                                    event is VFilePropertyChangeEvent &&
                                    event.isRename ||
                                    event is VFileMoveEvent
                            }.flatMap { event ->
                                when (event) {
                                    is com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent -> listOf("${event.newParent.path}/${event.newChildName}")
                                    is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
                                    is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else emptyList()
                                    else -> listOf(event.path)
                                }
                            }.mapNotNull { runCatching { Path.of(it).normalize() }.getOrNull() }
                            .filter { isScanPath(it, roots) && (!isImagePath(it) || isResourceImagePath(it)) }
                    if (paths.isEmpty()) return
                    // Directories also matter: a rename or deletion can affect an entire image subtree.
                    invalidated.addAll(paths)
                    if (autoCheck) timer.restart()
                }
            },
        )
    }

    fun setOptions(
        dimensions: Boolean,
        tint: Boolean,
    ) {
        options = MatchOptions(dimensions, tint)
        properties.setValue("duplicateImages.dimensions", dimensions, false)
        properties.setValue("duplicateImages.tint", tint, false)
        scan()
    }

    fun setAutoCheck(enabled: Boolean) {
        autoCheck = enabled
        properties.setValue("duplicateImages.auto", enabled, true)
        if (enabled) scan() else timer.stop()
    }

    fun cancelScan() {
        timer.stop()
        progressTimer.stop()
        pendingProgress.set(null)
        generation++
        task?.cancel(false)
        snapshot = snapshot.copy(scanning = false, progress = null, message = "Scan cancelled · Showing previous results")
        listeners.forEach { it() }
    }

    fun scan(automatic: Boolean = false) {
        if (disposed || project.isDisposed) return
        timer.stop()
        task?.cancel(false)
        val token = ++generation
        val changed = invalidated.toSet()
        val matchOptions = options
        val baselineMatches = previousMatches
        pendingProgress.set(null)
        snapshot = snapshot.copy(scanning = true, message = "Finding images…", progress = ScanProgress("Finding images", 0))
        progressTimer.start()
        listeners.forEach { it() }
        task =
            executor.submit {
                try {
                    fun checkCancelled() {
                        if (disposed || token != generation) throw CancellationException()
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    }
                    var lastProgressTime = 0L
                    var lastPhase = ""

                    fun report(phase: String, completed: Long, total: Long? = null) {
                        checkCancelled()
                        val now = System.nanoTime()
                        if (phase != lastPhase || completed == total || now - lastProgressTime >= 100_000_000L) {
                            pendingProgress.set(token to ScanProgress(phase, completed, total))
                            lastProgressTime = now
                            lastPhase = phase
                        }
                    }
                    val (scanRoots, excludedRoots) =
                        ReadAction.computeBlocking<Pair<List<Path>, List<Path>>, RuntimeException> {
                            val rootManager = ProjectRootManager.getInstance(project)
                            val content = rootManager.contentRoots.mapNotNull { it.toNioPathOrNull() }
                            val base = project.basePath?.let { Path.of(it) }
                            val excluded =
                                com.intellij.openapi.module.ModuleManager.getInstance(project).modules.flatMap {
                                    com.intellij.openapi.roots.ModuleRootManager.getInstance(it).excludeRoots.mapNotNull { file ->
                                        file.toNioPathOrNull()
                                    }
                                }
                            (content.ifEmpty { listOfNotNull(base) }).distinct() to excluded
                        }
                    ApplicationManager.getApplication().invokeLater { if (!disposed && token == generation) roots = scanRoots }
                    cache.keys.removeIf { path -> changed.any { path.startsWith(it) } }

                    val files = linkedSetOf<Path>()
                    val issues = mutableListOf<String>()
                    var visits = 0
                    var limited = false
                    for (root in scanRoots) {
                        if (!Files.isDirectory(root)) continue
                        Files.walkFileTree(
                            root,
                            object : SimpleFileVisitor<Path>() {
                                override fun preVisitDirectory(
                                    dir: Path,
                                    attrs: BasicFileAttributes,
                                ): FileVisitResult {
                                    checkCancelled()
                                    report("Finding images", files.size.toLong())
                                    if (++visits > 100_000 || files.size >= MAX_IMAGES) {
                                        limited = true
                                        return FileVisitResult.TERMINATE
                                    }
                                    if (!isScanPath(dir, scanRoots) ||
                                        excludedRoots.any { dir.startsWith(it) }
                                    ) {
                                        return FileVisitResult.SKIP_SUBTREE
                                    }
                                    // Prime directory children so IDE VFS can report newly added images.
                                    ReadAction.computeBlocking<Unit, RuntimeException> {
                                        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(dir)?.children
                                        Unit
                                    }
                                    return FileVisitResult.CONTINUE
                                }

                                override fun visitFile(
                                    file: Path,
                                    attrs: BasicFileAttributes,
                                ): FileVisitResult {
                                    checkCancelled()
                                    if (attrs.isRegularFile && isResourceImagePath(file) && isScanPath(file, scanRoots)) files.add(file)
                                    report("Finding images", files.size.toLong())
                                    if (++visits > 100_000 || files.size >= MAX_IMAGES) {
                                        limited = true
                                        return FileVisitResult.TERMINATE
                                    }
                                    return FileVisitResult.CONTINUE
                                }

                                override fun visitFileFailed(
                                    file: Path,
                                    exc: IOException,
                                ): FileVisitResult {
                                    if (issues.size < 200) issues.add("$file: ${exc.message}")
                                    return FileVisitResult.CONTINUE
                                }
                            },
                        )
                        if (limited) break
                    }
                    if (limited) {
                        issues.add(
                            "Scan limit reached: at most $MAX_IMAGES images and 100,000 filesystem entries. Results are incomplete.",
                        )
                    }
                    cache.keys.retainAll(files)
                    report("Reading images", 0, files.size.toLong())
                    val images =
                        files.sorted().mapIndexedNotNull { index, file ->
                            checkCancelled()
                            report("Reading images", index.toLong(), files.size.toLong())
                            try {
                                val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
                                val stamp = attrs.lastModifiedTime().toString() + ":" + attrs.size()
                                val old = cache[file]
                                if (old != null && old.stamp == stamp) {
                                    old.image
                                } else {
                                    val image = readImage(file)
                                    cache[file] = CachedImage(stamp, image)
                                    image
                                }
                            } catch (e: IOException) {
                                if (issues.size < 200) issues.add("$file: ${e.message}")
                                null
                            } catch (e: RuntimeException) {
                                if (issues.size < 200) issues.add("$file: ${e.message ?: "Cannot decode image"}")
                                null
                            }
                        }
                    report("Reading images", files.size.toLong(), files.size.toLong())
                    val result = compareAll(images, matchOptions, progress = { done, total -> report("Comparing pairs", done, total) }, cancelled = ::checkCancelled)
                    if (result.limited) issues.add("20,000 matching pairs reached. Results are incomplete.")
                    checkCancelled()
                    val next =
                        ScanSnapshot(
                            result.groups,
                            images.size,
                            issues,
                            message =
                                "${result.groups.size} groups · ${images.size} images checked" +
                                    if (issues.isEmpty()) "" else " · ${issues.size} scan issues",
                        )
                    report("Preparing results", 0, 1)
                    val edges = next.groups.flatMap { it.matches }
                    val newEdges = if (automatic) newlyMatchedPairs(next.groups, baselineMatches) else emptyList()
                    val nextMatchKeys = edges.mapTo(mutableSetOf()) { it.key }
                    checkCancelled()
                    ApplicationManager.getApplication().invokeLater {
                        if (disposed || project.isDisposed || token != generation) return@invokeLater
                        progressTimer.stop()
                        pendingProgress.set(null)
                        invalidated.removeAll(changed)
                        snapshot = next
                        previousMatches = nextMatchKeys
                        listeners.forEach { it() }
                        if (automatic && initialized && newEdges.isNotEmpty()) notifyMatches(newEdges)
                        initialized = true
                    }
                } catch (_: CancellationException) {
                    // Superseded scans stop cooperatively without interrupting the shared pool thread.
                } catch (_: InterruptedException) {
                    Thread.interrupted()
                } catch (e: Exception) {
                    Logger.getInstance(ImageScanService::class.java).warn("Image scan failed", e)
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed && token == generation) {
                            progressTimer.stop()
                            pendingProgress.set(null)
                            snapshot =
                                snapshot.copy(
                                    scanning = false,
                                    progress = null,
                                    message = "Scan failed. Retry Scan images.",
                                    issues =
                                        listOf(
                                            e.message ?: e.javaClass.simpleName,
                                        ),
                                )
                            listeners.forEach { it() }
                        }
                    }
                }
            }
    }

    private fun notifyMatches(matches: List<ImageMatch>) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(TOOL_WINDOW)
            .createNotification(
                "Possible duplicate images found",
                "${matches.size} new matching pairs. Review dimensions and HEX colors before changing resources.",
                NotificationType.WARNING,
            ).addAction(
                NotificationAction.createSimpleExpiring("View matches") {
                    focusPath = matches.first().first.path
                    ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)?.activate { listeners.forEach { it() } }
                },
            ).notify(project)
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        progressTimer.stop()
        pendingProgress.set(null)
        task?.cancel(false)
        executor.shutdownNow()
        listeners.clear()
    }

    private data class CachedImage(
        val stamp: String,
        val image: ImageEntry,
    )
}

private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): Path? =
    if (isInLocalFileSystem) {
        runCatching {
            toNioPath()
        }.getOrNull()
    } else {
        null
    }

fun newlyMatchedPairs(groups: List<ImageGroup>, previousKeys: Set<String>): List<ImageMatch> =
    groups.flatMap { it.matches }.filter { it.key !in previousKeys }
