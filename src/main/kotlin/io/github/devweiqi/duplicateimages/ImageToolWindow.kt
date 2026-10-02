package io.github.devweiqi.duplicateimages

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel

class ImageToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val service = project.getService(ImageScanService::class.java)
        val panel = ImagePanel(project, service)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.search
        toolWindow.contentManager.addContent(content)
        if (!service.snapshot.scanning && service.snapshot.imageCount == 0) service.scan()
    }
}

class ImagePanel(
    private val project: Project,
    private val service: ImageScanService,
) : JPanel(BorderLayout()),
    Disposable {
    val search = JBTextField()
    private val groupModel = DefaultListModel<ImageGroup>()
    private val groups = JBList(groupModel)
    private val imageModel = DefaultListModel<ImageEntry>()
    private val images = JBList(imageModel)
    private val tableModel =
        object : DefaultTableModel(arrayOf("Images", "Dimensions", "Tint / Main colors (HEX)", "Result"), 0) {
            override fun isCellEditable(
                row: Int,
                column: Int,
            ): Boolean = false
        }
    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val column = columnAtPoint(event.point)
            return if (row >= 0 && column >= 0) getValueAt(row, column)?.toString() else null
        }
    }
    private val status = textLabel("")
    private val heading = textLabel("Select an image group")
    private val path = JBTextField().apply { isEditable = false }
    private val color = JLabel()
    private val dimensions = JBCheckBox("Include different dimensions", service.options.dimensions)
    private val tint = JBCheckBox("Include different Tint", service.options.tint)
    private val auto = JBCheckBox("Auto-check changes", service.autoCheck)
    private val scan = JButton("Scan images")
    private val issues = JButton("Scan issues")
    private val open = JButton("Open file")
    private val copy = JButton("Copy path")
    private val actions = JButton("IDE actions…")
    private var updating = false
    private var current: ImageGroup? = null
    private var shownPairs = emptyList<Pair<ImageEntry, ImageEntry>>()
    private val listener: () -> Unit = { refresh() }

    init {
        border = JBUI.Borders.empty(6)
        val controls =
            JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), JBUI.scale(4))).apply {
                add(scan)
                add(auto)
            }
        controls.toolTipText =
            "Defaults to identical files or pixels. Enable both filters to include pairs with both dimension and Tint differences."
        val top = JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(
                JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
                    add(dimensions)
                    add(tint)
                },
                BorderLayout.SOUTH
            )
        }
        add(top, BorderLayout.NORTH)
        search.emptyText.text = "Search filenames or paths…"
        search.accessibleContext.accessibleName = "Search duplicate image groups"
        search.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = filterGroups()
            },
        )
        groups.selectionMode = ListSelectionModel.SINGLE_SELECTION
        groups.emptyText.text = "No matches for the current filters"
        groups.cellRenderer = GroupRenderer()
        groups.addListSelectionListener { if (!it.valueIsAdjusting && !updating) showGroup(groups.selectedValue) }
        val left =
            JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                border = JBUI.Borders.emptyRight(8)
                add(search, BorderLayout.NORTH)
                add(JBScrollPane(groups), BorderLayout.CENTER)
                minimumSize = JBUI.size(180, 0)
            }
        images.layoutOrientation = JList.HORIZONTAL_WRAP
        images.visibleRowCount = -1
        images.fixedCellWidth = JBUI.scale(220)
        images.fixedCellHeight = JBUI.scale(232)
        images.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        images.cellRenderer =
            ImageRenderer { image ->
                current
                    ?.images
                    ?.indexOf(image)
                    ?.let(::imageId)
                    .orEmpty()
            }
        images.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        images.accessibleContext.accessibleName = "Images in selected group. Select a file to inspect its full path."
        val imageScroll =
            JBScrollPane(images).apply {
                preferredSize = JBUI.size(650, 255)
                minimumSize = JBUI.size(0, 140)
            }
        heading.border = JBUI.Borders.empty(6, 8, 12, 8)
        val imageSection =
            JPanel(BorderLayout()).apply {
                add(heading, BorderLayout.NORTH)
                add(imageScroll, BorderLayout.CENTER)
            }
        val fileControls =
            JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                add(open)
                add(copy)
                add(actions)
                add(color)
            }
        val selection =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6)
                add(path, BorderLayout.NORTH)
                add(fileControls, BorderLayout.CENTER)
            }
        path.accessibleContext.accessibleName = "Selected image full path"
        imageSection.add(selection, BorderLayout.SOUTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = javax.swing.JTable.AUTO_RESIZE_ALL_COLUMNS
        listOf(70, 180, 220, 200).forEachIndexed { index, width ->
            table.columnModel.getColumn(index).minWidth = JBUI.scale(width)
        }
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(70)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(220)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(230)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(250)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && table.selectedRow in shownPairs.indices) {
                val pair = shownPairs[table.selectedRow]
                val entries = current?.images.orEmpty()
                images.selectedIndices = intArrayOf(entries.indexOf(pair.first), entries.indexOf(pair.second))
            }
        }
        val comparison =
            JPanel(BorderLayout()).apply {
                add(
                    textLabel("Pairwise comparison · Each pair is checked independently").apply { border = JBUI.Borders.empty(8) },
                    BorderLayout.NORTH,
                )
                add(JBScrollPane(table), BorderLayout.CENTER)
            }
        val right =
            JPanel(BorderLayout()).apply {
                add(
                    OnePixelSplitter(true, 0.63f).apply {
                        firstComponent = imageSection
                        secondComponent = comparison
                    },
                    BorderLayout.CENTER,
                )
                add(
                    textLabel("Suggested matches can be intentional variants. No files are modified.").apply {
                        border = JBUI.Borders.empty(8)
                    },
                    BorderLayout.SOUTH,
                )
            }
        add(
            OnePixelSplitter(false, 0.24f).apply {
                firstComponent = left
                secondComponent = right
            },
            BorderLayout.CENTER,
        )
        val bottom =
            JPanel(BorderLayout()).apply {
                add(status, BorderLayout.CENTER)
                add(issues, BorderLayout.EAST)
            }
        add(bottom, BorderLayout.SOUTH)
        scan.addActionListener { service.scan() }
        dimensions.addActionListener { service.setOptions(dimensions.isSelected, tint.isSelected) }
        tint.addActionListener { service.setOptions(dimensions.isSelected, tint.isSelected) }
        auto.addActionListener { service.setAutoCheck(auto.isSelected) }
        issues.addActionListener { Messages.showInfoMessage(project, service.snapshot.issues.joinToString("\n"), "Image scan issues") }
        open.addActionListener {
            selectedImage()?.let { image ->
                LocalFileSystem
                    .getInstance()
                    .findFileByNioFile(
                        image.path,
                    )?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                    ?: Messages.showInfoMessage(project, "File is no longer available. Scan images again.", TOOL_WINDOW)
            }
        }
        copy.addActionListener { selectedImage()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.path.toString())) } }
        actions.addActionListener { showActions() }
        service.listeners.add(listener)
        refresh()
    }

    private fun selectedImage(): ImageEntry? = images.selectedValuesList.singleOrNull()

    private fun showSelected() {
        val image = selectedImage()
        path.text = image?.path?.toString() ?: if (images.selectedIndices.size > 1) "Two images selected for comparison" else ""
        path.caretPosition = 0
        color.text = image?.let { (if (it.monochrome) "Tint: " else "Main colors: ") + it.colorText }.orEmpty()
        color.icon = image?.colors?.takeIf { it.isNotEmpty() }?.let(::ColorSwatches)
        open.isEnabled = image != null
        copy.isEnabled = image != null
        actions.isEnabled = image != null
    }

    private fun refresh() {
        val snapshot = service.snapshot
        status.text = snapshot.message
        scan.isEnabled = !snapshot.scanning
        issues.isVisible = snapshot.issues.isNotEmpty()
        filterGroups()
    }

    private fun filterGroups() {
        val selectedPath =
            service.focusPath ?: groups.selectedValue
                ?.images
                ?.firstOrNull()
                ?.path
        val query = if (service.focusPath != null) "" else search.text.trim()
        updating = true
        groupModel.clear()
        service.snapshot.groups
            .filter { group ->
                group.images.any { it.path.toString().contains(query, ignoreCase = true) }
            }.forEach(groupModel::addElement)
        val index =
            (0 until groupModel.size()).firstOrNull { i -> groupModel[i].images.any { it.path == selectedPath } }
                ?: if (groupModel.isEmpty) -1 else 0
        groups.selectedIndex = index
        updating = false
        service.focusPath = null
        showGroup(groups.selectedValue)
    }

    private fun showGroup(group: ImageGroup?) {
        current = group
        imageModel.clear()
        tableModel.rowCount = 0
        shownPairs = emptyList()
        heading.text =
            if (group ==
                null
            ) {
                "No matches · Enable dimensions or Tint to broaden the check"
            } else {
                "${group.images.first().path.fileName} · ${group.images.size} images"
            }
        if (group == null) {
            showSelected()
            return
        }
        // Only the pair table is capped; all images remain available in the card list.
        group.images.forEach(imageModel::addElement)
        images.selectedIndex = 0
        val pairs = mutableListOf<Pair<ImageEntry, ImageEntry>>()
        val matchByPair = group.matches.associateBy { setOf(it.first.path, it.second.path) }
        val rows = minOf(group.images.size, 64)
        for (i in 0 until rows) {
            for (j in i + 1 until rows) {
                val a = group.images[i]
                val b = group.images[j]
                val match = matchByPair[setOf(a.path, b.path)]
                val colorText = if (a.colorText == b.colorText) a.colorText else "${a.colorText} → ${b.colorText}"
                tableModel.addRow(
                    arrayOf(
                        "${imageId(i)} ↔ ${imageId(j)}",
                        if (a.dimensions ==
                            b.dimensions
                        ) {
                            a.dimensions
                        } else {
                            "${a.dimensions} → ${b.dimensions}"
                        },
                        colorText,
                        match?.description ?: "No direct match under current filters",
                    ),
                )
                pairs.add(a to b)
            }
        }
        shownPairs = pairs
        if (group.images.size > 64) heading.text += " · Pair table limited to first 64 images"
    }

    private fun showActions() {
        val image = selectedImage() ?: return
        val file = LocalFileSystem.getInstance().findFileByNioFile(image.path) ?: return
        val psi = ReadAction.computeBlocking<com.intellij.psi.PsiFile?, RuntimeException> { PsiManager.getInstance(project).findFile(file) }
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.CONTEXT_COMPONENT, images)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .add(CommonDataKeys.PSI_FILE, psi)
                .add(CommonDataKeys.PSI_ELEMENT, psi)
                .build()
        val manager = ActionManager.getInstance()
        val menu = DefaultActionGroup()
        listOf("FindUsages", "SelectIn", "RevealIn").forEach { id -> manager.getAction(id)?.let(menu::add) }
        manager.createActionPopupMenu("DuplicateImages.File", menu).apply {
            setDataContext { context }
            component.show(actions, 0, actions.height)
        }
    }

    override fun dispose() {
        service.listeners.remove(listener)
    }
}

private fun textLabel(text: String): JLabel = JLabel(text).apply { putClientProperty("html.disable", true) }

private fun imageId(index: Int): String = if (index < 26) ('A' + index).toString() else (index + 1).toString()

private class GroupRenderer : ListCellRenderer<ImageGroup> {
    override fun getListCellRendererComponent(
        list: JList<out ImageGroup>,
        value: ImageGroup,
        index: Int,
        selected: Boolean,
        focus: Boolean,
    ): Component {
        val types =
            buildList {
                if (value.matches.any { it.exact || it.first.pixelHash == it.second.pixelHash }) add("Identical")
                if (value.matches.any { it.resized }) add("Dimensions")
                if (value.matches.any { it.tinted }) add("Tint")
            }.joinToString(" + ")
        return RendererPanel(BorderLayout(10, 0)).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(12, 8))
            background = if (selected) list.selectionBackground else list.background
            add(JLabel(ImageIcon(value.images.first(), 44)), BorderLayout.WEST)
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(
                        textLabel(
                            value.images
                                .first()
                                .path.fileName
                                .toString(),
                        ).apply {
                            foreground =
                                if (selected) list.selectionForeground else list.foreground
                        },
                    )
                    add(
                        textLabel("${value.images.size} images · $types").apply {
                            foreground =
                                if (selected) list.selectionForeground else JBColor.GRAY
                            ; border = JBUI.Borders.emptyTop(5)
                        },
                    )
                },
                BorderLayout.CENTER,
            )
        }
    }
}

private class ImageRenderer(
    private val id: (ImageEntry) -> String,
) : ListCellRenderer<ImageEntry> {
    override fun getListCellRendererComponent(
        list: JList<out ImageEntry>,
        image: ImageEntry,
        index: Int,
        selected: Boolean,
        focus: Boolean,
    ): Component =
        RendererPanel(BorderLayout()).apply {
            background = list.background
            border =
                BorderFactory.createCompoundBorder(
                    JBUI.Borders.empty(6),
                    BorderFactory.createLineBorder(
                        if (selected) list.selectionBackground else JBColor.border(),
                        JBUI.scale(if (selected) 2 else 1),
                    ),
                )
            add(JLabel(ImageIcon(image, 110)).apply { preferredSize = JBUI.size(200, 125) }, BorderLayout.NORTH)
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(4, 8)
                    add(textLabel("${id(image)} · ${image.path.fileName}").apply { toolTipText = text })
                    add(textLabel("${image.dimensions} · ${"%.1f".format(image.bytes / 1024.0)} KB"))
                    add(
                        textLabel(
                            image.path.parent.fileName
                                .toString(),
                        ).apply { foreground = JBColor.GRAY },
                    )
                    add(
                        textLabel(image.colorText).apply {
                            icon = ColorSwatches(image.colors)
                            toolTipText =
                                if (image.monochrome) "Tint" else "Main colors"
                        },
                    )
                    toolTipText = image.path.toString()
                },
                BorderLayout.CENTER,
            )
        }
}

private class ColorSwatches(
    private val colors: List<Int>,
) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(colors.size * 14)

    override fun getIconHeight(): Int = JBUI.scale(12)

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        colors.forEachIndexed { i, color ->
            g.color = Color(color)
            g.fillRect(x + JBUI.scale(i * 14), y, JBUI.scale(10), JBUI.scale(10))
            g.color = JBColor.border()
            g.drawRect(x + JBUI.scale(i * 14), y, JBUI.scale(10), JBUI.scale(10))
        }
    }
}

private class ImageIcon(
    private val image: ImageEntry,
    private val size: Int,
) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(size)

    override fun getIconHeight(): Int = JBUI.scale(size)

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val canvas = g.create(x, y, iconWidth, iconHeight) as Graphics2D
        try {
            val step = JBUI.scale(8)
            for (row in 0..iconHeight / step) {
                for (col in 0..iconWidth / step) {
                    canvas.color =
                        if ((row + col) % 2 ==
                            0
                        ) {
                            JBColor(Color(230, 230, 230), Color(55, 57, 62))
                        } else {
                            JBColor(Color(250, 250, 250), Color(45, 47, 51))
                        }
                    canvas.fillRect(col * step, row * step, step, step)
                }
            }
            val scale = minOf(iconWidth.toDouble() / image.preview.width, iconHeight.toDouble() / image.preview.height)
            val width = (image.preview.width * scale).toInt()
            val height = (image.preview.height * scale).toInt()
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            canvas.drawImage(image.preview, (iconWidth - width) / 2, (iconHeight - height) / 2, width, height, null)
        } finally {
            canvas.dispose()
        }
    }
}

// Cell renderers are stamped without a displayable peer; lay out nested labels before painting.
private class RendererPanel(layout: java.awt.LayoutManager) : JPanel(layout) {
    override fun paint(graphics: Graphics) {
        fun layoutChildren(container: java.awt.Container) {
            container.doLayout()
            container.components.filterIsInstance<java.awt.Container>().forEach(::layoutChildren)
        }
        layoutChildren(this)
        super.paint(graphics)
    }
}

private fun sourceLabel(path: Path): String {
    val parts = path.map { it.toString() }
    val src = parts.indexOfLast { it == "src" }
    return if (src > 0 && src + 1 < parts.size) "${parts[src - 1]}.${parts[src + 1]}" else path.parent.fileName.toString()
}
