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
    // 行内版本差徽标：实体自身版本名与台账对侧版本名
    val versionName: String = "",
    val backedUpVersionName: String = "",
)

/**
 * 行内版本差文本：备份页"本机 → 备份"，恢复页"本机 → 云端备份"（实体侧即云端版本）。
 * 非待更新或版本名缺失时返回 null，由调用方决定是否降级为仅图标。
 */
fun buildVersionTransition(opType: OpType, app: App): String? {
    if (app.isOutdated.not() || app.versionName.isEmpty() || app.backedUpVersionName.isEmpty()) return null
    return when (opType) {
        OpType.BACKUP -> "${app.versionName} → ${app.backedUpVersionName}"
        OpType.RESTORE -> "${app.backedUpVersionName} → ${app.versionName}"
    }
}
