package io.pcla.core.asset

import io.pcla.core.json.MiniJson

/** assets/indexes/<name>.json 里的一个对象。 */
data class AssetObject(val name: String, val hash: String, val size: Long)

object AssetIndex {
    fun parse(text: String): List<AssetObject> {
        val root = MiniJson.parse(text) as? Map<*, *> ?: error("asset index 顶层不是对象")
        val objects = root["objects"] as? Map<*, *> ?: return emptyList()
        return objects.mapNotNull { (k, v) ->
            val name = k as? String ?: return@mapNotNull null
            val m = v as? Map<*, *> ?: return@mapNotNull null
            val hash = m["hash"] as? String ?: return@mapNotNull null
            AssetObject(name, hash, (m["size"] as? Number)?.toLong() ?: 0L)
        }
    }

    /** 对象在磁盘上的相对路径：objects/ab/abcdef...（前两位做分片目录）。 */
    fun relativePath(hash: String): String {
        if (hash.length < 2) return "objects/$hash"
        return "objects/${hash.substring(0, 2)}/$hash"
    }
}
