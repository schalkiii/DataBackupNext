package com.xayah.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ListDataRepoSourceScopeTest {

    private fun resolve(
        cloudName: String = "",
        backupDir: String = "/local",
        savedCloud: String = "",
        savedBackupDir: String = "",
        isSavedCloudAlive: Boolean = true,
    ) = ListDataRepo.resolveSourceScope(cloudName, backupDir, savedCloud, savedBackupDir, isSavedCloudAlive)

    // 默认入口：无记忆时保持入口默认（本机）
    @Test
    fun defaultEntryWithoutMemoryFallsBackToLocal() {
        assertEquals("" to "/local", resolve())
    }

    // 默认入口：恢复上次选择的云端
    @Test
    fun defaultEntryRestoresRememberedCloud() {
        assertEquals("FTP" to "/remote", resolve(savedCloud = "FTP", savedBackupDir = "/remote"))
    }

    // 默认入口：恢复上次选择的本机
    @Test
    fun defaultEntryRestoresRememberedLocal() {
        assertEquals("" to "/local", resolve(savedCloud = "", savedBackupDir = "/local"))
    }

    // 默认入口：记忆中的云端已被删除时回退本机
    @Test
    fun defaultEntryFallsBackWhenRememberedCloudIsGone() {
        assertEquals("" to "/local", resolve(savedCloud = "FTP", savedBackupDir = "/remote", isSavedCloudAlive = false))
    }

    // 显式入口：入口指定云端时优先于记忆
    @Test
    fun explicitEntryOverridesMemory() {
        assertEquals("WebDAV" to "/backup", resolve(cloudName = "WebDAV", backupDir = "/backup", savedCloud = "FTP", savedBackupDir = "/remote"))
    }
}
