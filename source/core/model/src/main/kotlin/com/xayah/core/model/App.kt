package com.xayah.core.model

data class App(
    val id: Long,
    val packageName: String,
    val label: String,
    val preserveId: Long,
    val isSystemApp: Boolean,
    val selectionFlag: Int,
    val selected: Boolean,
    // 派生台账状态（无备份时为默认值）
    val lastBackupTime: Long = 0L,
    val backedUpVersionCode: Long = 0L,
    val copyCount: Int = 0,
    val isOutdated: Boolean = false,
)
