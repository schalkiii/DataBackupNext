package com.xayah.core.model

import com.xayah.core.model.database.PackageEntity
import kotlinx.serialization.Serializable

// 云端应用索引清单版本，高于本机版本时调用方需降级为全量扫描
const val CloudIndexSchemaVersion = 1

@Serializable
data class CloudIndexManifest(
    val schemaVersion: Int = CloudIndexSchemaVersion,
    val generatedAt: Long = 0L,
    val packages: List<PackageEntity> = listOf(),
)
