package io.pcla.core.game

import io.pcla.core.asset.AssetIndex
import io.pcla.core.asset.AssetObject
import io.pcla.core.launch.Library
import io.pcla.core.launch.Platform
import io.pcla.core.launch.VersionMeta

/** One file to download: remote URL + relative on-disk path + checksum. */
data class RemoteFile(
    val relativePath: String,
    val url: String,
    val sha1: String?,
    val size: Long?,
    /** Category, used only for UI grouping and human-readable progress */
    val kind: Kind,
) {
    enum class Kind { LIBRARY, ASSET, CLIENT }
}

/**
 * Turns a version json + assets index into "which files to download, where they go, how they verify".
 * Pure function: no network and no disk, so edge cases can be exercised with fake data in a container.
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
 * Re-checks every file by sha1 after download. Returns the failing files (empty = all good).
 * Reads disk only, never fixes or deletes — whether to delete is the caller's call.
 */
object GameVerifier {
    fun verify(files: List<RemoteFile>, root: java.io.File, hasher: (java.io.File) -> String): List<RemoteFile> =
        files.filter { f ->
            val expected = f.sha1 ?: return@filter false
            val target = java.io.File(root, f.relativePath)
            !target.isFile || !hasher(target).equals(expected, ignoreCase = true)
        }
}
