package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.SlotGeometry
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT

/** One recorded layout shared by panels, main-task fitting and all preview displays. */
data class WorkspaceLayoutProfile(
    val geometry: WorkspaceGeometry,
    val densityDpi: Int,
    val gapPixels: Int,
) {
    data class Slot(val width: Int, val height: Int, val top: Int)
    data class Source(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    data class PreviewTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)

    init {
        require(densityDpi > 0 && gapPixels > 0)
        require(geometry.sidebarWidth > gapPixels)
    }

    private val leftGeometry = geometry.copy(sidebarSide = SidebarSide.LEFT)
    private val rightGeometry = geometry.copy(sidebarSide = SidebarSide.RIGHT)
    val canvas = SlotGeometry.forRecordedDisplay(geometry.availableWidth, geometry.availableHeight, densityDpi)
    // Apps lay out at the recorded usable resolution, not the dimensions of a side slot.
    val source = Source(0, 0, canvas.width, canvas.height)

    /**
     * A slot count is part of the saved profile's wire format. Always generate
     * the historical three-slot geometry when validating V2-V5 records, even
     * though the current workspace presents four slots.
     */
    private fun slotsForCount(count: Int): List<Slot> {
        require(count in 3..6)
        val availableHeight = (geometry.sidebarLogicalHeight - gapPixels * (count - 1))
            .coerceAtLeast(count)
        val base = availableHeight / count
        val remainder = availableHeight % count
        return List(count) { index ->
            Slot(
                width = geometry.sidebarWidth - gapPixels,
                height = base + if (index < remainder) 1 else 0,
                top = index * (base + gapPixels) + minOf(index, remainder),
            )
        }
    }

    val slots: List<Slot> = slotsForCount(WORKSPACE_SLOT_COUNT)

    fun geometry(side: SidebarSide): WorkspaceGeometry =
        if (side == SidebarSide.LEFT) leftGeometry else rightGeometry

    fun rotatedTo(rotation: Int): WorkspaceLayoutProfile =
        if (rotation == geometry.displayRotation) this else copy(geometry = geometry.rotatedTo(rotation))

    /** Undo TextureView's default stretching while preserving the recorded canvas. */
    fun previewTransform(width: Int, height: Int): PreviewTransform {
        require(width > 0 && height > 0)
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        return PreviewTransform(
            canvas.width * scale / width,
            canvas.height * scale / height,
            (width - source.width * scale) / 2f - source.left * scale,
            (height - source.height * scale) / 2f - source.top * scale,
        )
    }

    private fun encodeForVersion(version: String, count: Int, includeRotation: Boolean): String {
        val fields = mutableListOf<Any>(
            version, geometry.screenWidth, geometry.screenHeight,
            geometry.topHeight, geometry.sidebarWidth, densityDpi, gapPixels,
            geometry.navigationInsets.left, geometry.navigationInsets.top,
            geometry.navigationInsets.right, geometry.navigationInsets.bottom,
            geometry.contentWidth, geometry.contentHeight, geometry.contentScale,
            canvas.width, canvas.height,
            source.left, source.top, source.right, source.bottom,
        )
        slotsForCount(count).forEach { slot ->
            fields.add(slot.width)
            fields.add(slot.height)
            fields.add(slot.top)
        }
        if (includeRotation) fields.add(geometry.displayRotation)
        return fields.joinToString(",")
    }

    /** V6 records four side slots and their rotation. V2-V5 remain readable. */
    fun encode(): String = encodeForVersion("6", WORKSPACE_SLOT_COUNT, includeRotation = true)

    companion object {
        fun create(
            width: Int,
            height: Int,
            densityDpi: Int,
            insets: WorkspaceNavigationInsets,
            displayRotation: Int = 0,
        ) = WorkspaceLayoutProfile(
            WorkspaceGeometry.forDisplay(
                width,
                height,
                density = densityDpi / 160f,
                navigationInsets = insets,
                displayRotation = displayRotation,
            ),
            densityDpi,
            (densityDpi / 160f).toInt().coerceAtLeast(1),
        )

        fun decode(encoded: String?): WorkspaceLayoutProfile? = runCatching {
            val p = encoded?.split(',') ?: return@runCatching null
            val version = p.firstOrNull() ?: return@runCatching null
            if (version !in setOf("2", "3", "4", "5", "6")) return@runCatching null
            val isCurrent = version == "6"
            val slotCount = if (isCurrent) WORKSPACE_SLOT_COUNT else 3
            val hasRotation = version == "5" || isCurrent
            val fieldCount = 20 + 3 * slotCount + (if (hasRotation) 1 else 0)
            if (p.size != fieldCount) return@runCatching null

            val recorded = WorkspaceLayoutProfile(
                WorkspaceGeometry(
                    p[1].toInt(), p[2].toInt(),
                    p[3].toInt(), p[4].toInt(), SidebarSide.RIGHT,
                    WorkspaceNavigationInsets(
                        p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt(),
                    ),
                    displayRotation = if (hasRotation) p.last().toInt() else 0,
                ),
                p[5].toInt(),
                p[6].toInt(),
            )
            val expected = recorded.encodeForVersion(version, slotCount, hasRotation)
                .split(',').toMutableList()
            if (version == "2") {
                // V2 recorded the physical full-screen buffer, not the usable
                // canvas. Validate it without consulting changing WindowMetrics.
                val oldCanvas = SlotGeometry.forRecordedDisplay(
                    recorded.geometry.screenWidth, recorded.geometry.screenHeight, recorded.densityDpi,
                )
                expected[14] = oldCanvas.width.toString()
                expected[15] = oldCanvas.height.toString()
                expected[18] = oldCanvas.width.toString()
                expected[19] = oldCanvas.height.toString()
            }
            if (expected != p) return@runCatching null

            if (isCurrent) recorded else create(
                recorded.geometry.screenWidth,
                recorded.geometry.screenHeight,
                recorded.densityDpi,
                recorded.geometry.navigationInsets,
                recorded.geometry.displayRotation,
            )
        }.getOrNull()
    }
}
