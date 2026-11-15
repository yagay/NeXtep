package io.github.lujinxin.nextep.workspace

/**
 * Privileged SystemUI -> system_server snapshot of the FOUR explicitly pinned tasks.
 * A package name alone never grants an exemption. The snapshot is bound to
 * Task ID, full per-user UID, user, slot and the current Android boot count.
 */
internal object PinnedTaskIdentityContract {
    data class Identity(
        val slotIndex: Int,
        val taskId: Int,
        val uid: Int,
        val userId: Int,
        val bootCount: Int,
        val packageName: String,
    )
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    private fun valid(value: Identity): Boolean =
        value.slotIndex in 0..3 && value.taskId > 0 &&
            value.uid >= 10000 && value.userId >= 0 &&
            value.uid / 100000 == value.userId &&
            value.bootCount >= 0 && packagePattern.matches(value.packageName)

    fun encode(identities: Collection<Identity>): String =
        identities.filter(::valid).distinctBy { it.slotIndex }
            .take(4).joinToString(";") {
                "${it.slotIndex}:${it.taskId}:${it.uid}:${it.userId}:${it.bootCount}:${it.packageName}"
            }

    fun decode(serialized: String?, currentBootCount: Int): Map<Int, Identity> {
        if (currentBootCount < 0 || serialized.isNullOrBlank() || serialized.length > 1024)
            return emptyMap()
        val items = serialized.split(';')
        if (items.size > 4) return emptyMap()
        val records = items.mapNotNull { item ->
            val parts = item.split(':')
            if (parts.size != 6) return@mapNotNull null
            val value = Identity(
                parts[0].toIntOrNull() ?: return@mapNotNull null,
                parts[1].toIntOrNull() ?: return@mapNotNull null,
                parts[2].toIntOrNull() ?: return@mapNotNull null,
                parts[3].toIntOrNull() ?: return@mapNotNull null,
                parts[4].toIntOrNull() ?: return@mapNotNull null,
                parts[5],
            )
            value.takeIf { valid(it) && it.bootCount == currentBootCount }
        }
        // Malformed/duplicate registrations fail closed instead of granting
        // exemptions to an ambiguous Task ID or reserved slot.
        if (records.size != items.size ||
            records.map { it.slotIndex }.distinct().size != records.size ||
            records.map { it.taskId }.distinct().size != records.size) return emptyMap()
        return records.associateBy { it.taskId }
    }

    fun matches(
        identity: Identity?,
        taskId: Int,
        uid: Int,
        packageName: String?,
        displayId: Int,
        ownedSlotIndex: Int?,
    ): Boolean = identity != null && valid(identity) &&
        taskId == identity.taskId && uid == identity.uid &&
        packageName == identity.packageName &&
        (displayId == 0 || ownedSlotIndex == identity.slotIndex)
}
