package io.pcla.core.launch

import io.pcla.core.json.MiniJson

/**
 * The part of a version json PCLA actually needs. Deliberately not exhaustive:
 * download, verification and natives extraction each have their own module; this one only has to "read it right".
 */
data class VersionMeta(
    val id: String,
    val mainClass: String?,
    val inheritsFrom: String?,
    val assetsIndex: String?,
    val javaMajor: Int?,
    val assetIndexUrl: String?,
    val clientUrl: String?,
    val libraries: List<Library>,
    val jvmArgs: List<Arg>,
    val gameArgs: List<Arg>,
) {
    companion object {
        fun parse(text: String): VersionMeta {
            val root = MiniJson.parse(text) as? Map<*, *> ?: error("版本 json 顶层不是对象")
            val libs = (root["libraries"] as? List<*>).orEmpty().mapNotNull { Library.parse(it) }
            val args = root["arguments"] as? Map<*, *>
            val javaVersion = root["javaVersion"] as? Map<*, *>
            val assetIndex = root["assetIndex"] as? Map<*, *>
            val downloads = root["downloads"] as? Map<*, *>
            val client = downloads?.get("client") as? Map<*, *>
            return VersionMeta(
                id = root["id"] as? String ?: "",
                mainClass = root["mainClass"] as? String,
                inheritsFrom = root["inheritsFrom"] as? String,
                assetsIndex = root["assets"] as? String,
                javaMajor = (javaVersion?.get("majorVersion") as? Number)?.toInt(),
                assetIndexUrl = assetIndex?.get("url") as? String,
                clientUrl = client?.get("url") as? String,
                libraries = libs,
                jvmArgs = Arg.parseList(args?.get("jvm")),
                gameArgs = Arg.parseList(args?.get("game") ?: root["minecraftArguments"]),
            )
        }
    }
}

/** An argument is either a plain string or an object with rules. */
data class Arg(val value: String, val rules: List<Rule> = emptyList()) {
    companion object {
        fun parseList(raw: Any?): List<Arg> {
            if (raw is String) return raw.split(" ").filter { it.isNotBlank() }.map { Arg(it) }
            val list = raw as? List<*> ?: return emptyList()
            val out = ArrayList<Arg>()
            for (item in list) {
                when (item) {
                    is String -> out.add(Arg(item))
                    is Map<*, *> -> {
                        val rules = Rule.parseList(item["rules"])
                        when (val v = item["value"]) {
                            is String -> out.add(Arg(v, rules))
                            is List<*> -> v.filterIsInstance<String>().forEach { out.add(Arg(it, rules)) }
                        }
                    }
                }
            }
            return out
        }
    }
}

data class Rule(
    val action: String,
    val osName: String?,
    val osArch: String?,
    val features: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        fun parseList(raw: Any?): List<Rule> {
            val list = raw as? List<*> ?: return emptyList()
            val out = ArrayList<Rule>()
            for (item in list) {
                val m = item as? Map<*, *> ?: continue
                val os = m["os"] as? Map<*, *>
                val feats = LinkedHashMap<String, Boolean>()
                (m["features"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String) feats[k] = v == true }
                out.add(
                    Rule(
                        action = m["action"] as? String ?: "allow",
                        osName = os?.get("name") as? String,
                        osArch = os?.get("arch") as? String,
                        features = feats,
                    ),
                )
            }
            return out
        }
    }
}

/**
 * One library declaration. The old format uses a natives map, the new one uses classifiers;
 * both must be read — every json up to 1.16 uses the old format.
 */
data class Library(
    val name: String,
    val artifactPath: String?,
    val artifactUrl: String?,
    val artifactSize: Long?,
    val artifactSha1: String?,
    val nativeClassifier: String?,
    val nativePath: String?,
    val nativeUrl: String?,
    val nativeSha1: String?,
    val rules: List<Rule>,
    val extractExclude: List<String>,
) {
    val coordinate: LibCoordinate get() = LibCoordinate.parse(name)

    companion object {
        fun parse(raw: Any?): Library? {
            val m = raw as? Map<*, *> ?: return null
            val name = m["name"] as? String ?: return null
            val downloads = m["downloads"] as? Map<*, *>
            val artifact = downloads?.get("artifact") as? Map<*, *>
            val classifiers = downloads?.get("classifiers") as? Map<*, *>
            val nativesMap = m["natives"] as? Map<*, *>
            val classifierKey = nativesMap?.get("linux") as? String
                ?: nativesMap?.get("linux-arm64") as? String
            val nativeEntry = classifierKey?.let { classifiers?.get(it) as? Map<*, *> }
            val extract = m["extract"] as? Map<*, *>
            return Library(
                name = name,
                artifactPath = artifact?.get("path") as? String,
                artifactUrl = artifact?.get("url") as? String,
                artifactSize = (artifact?.get("size") as? Number)?.toLong(),
                artifactSha1 = artifact?.get("sha1") as? String,
                nativeClassifier = classifierKey,
                nativePath = nativeEntry?.get("path") as? String,
                nativeUrl = nativeEntry?.get("url") as? String,
                nativeSha1 = nativeEntry?.get("sha1") as? String,
                rules = Rule.parseList(m["rules"]),
                extractExclude = (extract?.get("exclude") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            )
        }
    }
}

data class LibCoordinate(val group: String, val artifact: String, val version: String, val classifier: String?) {
    val fileName: String
        get() = buildString {
            append(artifact); append('-'); append(version)
            classifier?.let { append('-'); append(it) }
            append(".jar")
        }

    companion object {
        fun parse(name: String): LibCoordinate {
            val parts = name.split(":")
            return LibCoordinate(
                group = parts.getOrElse(0) { "" },
                artifact = parts.getOrElse(1) { name },
                version = parts.getOrElse(2) { "" },
                classifier = if (parts.size > 3) parts[3] else null,
            )
        }
    }
}

/** Rule matching for the target platform. PCLA only runs Android/arm64, but the parameters stay for self-checks. */
object Platform {
    const val OS = "linux"
    const val ARCH = "aarch64"

    fun allows(rules: List<Rule>, os: String = OS, arch: String = ARCH): Boolean {
        if (rules.isEmpty()) return true
        for (r in rules) {
            if (matches(r, os, arch)) return r.action == "allow"
        }
        return false
    }

    fun matches(rule: Rule, os: String = OS, arch: String = ARCH): Boolean {
        if (rule.features.isNotEmpty() && rule.features.values.any { it }) return false // features are all off by default
        val wantOs = rule.osName ?: return true
        if (!wantOs.equals(os, ignoreCase = true)) return false
        val wantArch = rule.osArch ?: return true
        return when (wantArch.lowercase()) {
            "x86" -> arch.startsWith("x86") || arch == "i386"
            "x86_64", "amd64" -> arch == "x86_64"
            "arm64", "aarch64" -> arch == "aarch64"
            "arm" -> arch == "arm"
            else -> wantArch.equals(arch, ignoreCase = true)
        }
    }
}
