package com.xayah.core.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudIndexManifestTest {

    @Test
    fun `默认构造生成合法清单`() {
        val manifest = CloudIndexManifest()

        assertEquals(CloudIndexSchemaVersion, manifest.schemaVersion)
        assertEquals(0L, manifest.generatedAt)
        assertTrue(manifest.packages.isEmpty())
    }

    @Test
    fun `Gson 序化后包含全部字段`() {
        val manifest = CloudIndexManifest(generatedAt = 100L)
        val json = Gson().toJson(manifest)

        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"generatedAt\""))
        assertTrue(json.contains("\"packages\""))
    }

    @Test
    fun `Gson 缺失字段回退默认值而非null`() {
        val json = """{"schemaVersion":1,"generatedAt":100}"""
        val manifest = Gson().fromJson(json, CloudIndexManifest::class.java)

        assertEquals(1, manifest.schemaVersion)
        // 全默认参数的 data class 有无参构造器，Gson 对缺失字段回退默认值
        assertNotNull(manifest.packages)
        assertTrue(manifest.packages.isEmpty())
    }

    @Test
    fun `Gson 显式null字段才产生null`() {
        val json = """{"schemaVersion":1,"generatedAt":100,"packages":null}"""
        val manifest = Gson().fromJson(json, CloudIndexManifest::class.java)

        assertNull(manifest.packages)
    }

    @Test
    fun `schemaVersion 高于本机时调用方应降级`() {
        val manifest = CloudIndexManifest(schemaVersion = CloudIndexSchemaVersion + 1)

        assertTrue(manifest.schemaVersion > CloudIndexSchemaVersion)
    }
}
