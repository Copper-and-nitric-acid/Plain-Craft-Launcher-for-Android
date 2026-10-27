package io.pcla.core.game

import io.pcla.core.asset.AssetIndex
import io.pcla.core.asset.AssetObject
import io.pcla.core.launch.Library
import io.pcla.core.launch.Platform
import io.pcla.core.launch.VersionMeta

/** 一个待下载文件：远端地址 + 落盘相对路径 + 校验值。 */
data class RemoteFile(
    val relativePath: String,
    val url: String,
    val sha1: String?,
    val size: Long?,
    /** 归类，只用于 UI 分组与给出人话进度 */
    val kind: Kind,
) {
    enum class Kind { LIBRARY, ASSET, CLIENT }
}

/**
 * 把版本 json + assets 索引翻译成「要下哪些文件、放哪、怎么校验」。
 * 纯函数：不碰网络也不碰磁盘，方便在容器里用假数据把边界跑一遍。
 */
data class GamePlan(
    val libraries: List<RemoteFile>,
    val assets: List<RemoteFile>,
    val clientJar: RemoteFile?,
    val natives: List<RemoteFile>,
    val missingUrls: List<String>,
) {
    val all: List<RemoteFile> get() = libraries + natives + assets + listOfNotNull(clientJar)
    val totalBytes: Long get() = all.sumOf { it.size ?: 0L }
    val count: Int get() = all.size

    companion object {
        const val ASSET_BASE = "https://resources.download.minecraft.net"

        fun build(
            meta: VersionMeta,
            assets: List<AssetObject>,
            versionId: String,
            clientUrl: String? = null,
            assetBase: String = ASSET_BASE,
        ): GamePlan {
            val libs = ArrayList<RemoteFile>()
            val natives = ArrayList<RemoteFile>()
            val missing = ArrayList<String>()

            meta.libraries.forEach { lib ->
                if (!Platform.allows(lib.rules)) return@forEach
                if (lib.nativePath != null) {
                    val url = lib.nativeUrl
                    if (url == null) missing.add(lib.name) else {
                        natives.add(RemoteFile(lib.nativePath, url, lib.nativeSha1, lib.artifactSize, RemoteFile.Kind.LIBRARY))
                    }
                }
                val path = lib.artifactPath ?: return@forEach
                val url = lib.artifactUrl
                if (url == null) missing.add(lib.name) else {
                    libs.add(RemoteFile(path, url, lib.artifactSha1, lib.artifactSize, RemoteFile.Kind.LIBRARY))
                }
            }

            val assetFiles = assets.map { obj ->
                val rel = AssetIndex.relativePath(obj.hash)
                RemoteFile(rel, "$assetBase/${obj.hash.substring(0, 2)}/${obj.hash}", obj.hash, obj.size, RemoteFile.Kind.ASSET)
            }

            val client = clientUrl?.let {
                RemoteFile("versions/$versionId/$versionId.jar", it, null, null, RemoteFile.Kind.CLIENT)
            }

            return GamePlan(libs, assetFiles, client, natives, missing)
        }
    }
}

/**
 * 下载完成后按 sha1 逐个复核。返回不合格的文件列表（空表示全过）。
 * 只读磁盘，不修不删——删不删由上层拿主意。
 */
object GameVerifier {
    fun verify(files: List<RemoteFile>, root: java.io.File, hasher: (java.io.File) -> String): List<RemoteFile> =
        files.filter { f ->
            val expected = f.sha1 ?: return@filter false
            val target = java.io.File(root, f.relativePath)
            !target.isFile || !hasher(target).equals(expected, ignoreCase = true)
        }
}
