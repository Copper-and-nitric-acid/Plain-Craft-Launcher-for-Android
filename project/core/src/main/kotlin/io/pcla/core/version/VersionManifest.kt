package io.pcla.core.version

import io.pcla.core.json.MiniJson

/** One row of the official version_manifest_v2.json. */
data class VersionEntry(val id: String, val type: String, val url: String)

object VersionManifest {
    fun parse(text: String): List<VersionEntry> {
        val root = MiniJson.parse(text) as? Map<*, *> ?: error("manifest 顶层不是对象")
        val list = root["versions"] as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            val url = m["url"] as? String ?: return@mapNotNull null
            VersionEntry(id, m["type"] as? String ?: "release", url)
        }
    }

    fun find(entries: List<VersionEntry>, id: String): VersionEntry? =
        entries.firstOrNull { it.id == id }
            ?: entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
}
