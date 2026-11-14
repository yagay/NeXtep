package io.github.lujinxin.nextep.display

/**
 * Physical workspace slots and floating-mode slots are different
 * system_server policy domains. Never infer floating ownership from a
 * generic "NeXtep-slot" label: the legacy workspace must stay untouched.
 */
internal object FloatingSlotDisplayIdentity {
    private const val FLOATING_PREFIX = "NeXtep-floating-slot-"
    private const val WORKSPACE_PREFIX = "NeXtep-slot-"

    fun displayName(index: Int, floating: Boolean): String {
        require(index in 0 until WORKSPACE_SLOT_COUNT)
        return (if (floating) FLOATING_PREFIX else WORKSPACE_PREFIX) + index
    }

    fun belongsToFloatingDock(name: String): Boolean {
        if (!name.startsWith(FLOATING_PREFIX)) return false
        val slot = name.removePrefix(FLOATING_PREFIX).toIntOrNull() ?: return false
        return slot in 0 until WORKSPACE_SLOT_COUNT &&
            name == FLOATING_PREFIX + slot
    }
}
