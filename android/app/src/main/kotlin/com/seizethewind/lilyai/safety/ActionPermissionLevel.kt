package com.seizethewind.lilyai.safety

/** How much trust an action needs. Every tool declares one level. */
enum class ActionPermissionLevel {
    /** Runs immediately: reads harmless information. */
    SAFE,

    /** Changes something or touches private data: the user must approve each time. */
    CONFIRM,

    /** Never runs. */
    BLOCKED
}
