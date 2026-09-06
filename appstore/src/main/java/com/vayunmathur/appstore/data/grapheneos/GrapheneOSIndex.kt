package com.vayunmathur.appstore.data.grapheneos

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import org.json.JSONArray
import org.json.JSONObject

/** One downloadable file of a package: the base APK or one of its splits. */
data class GrapheneOSApk(
    val name: String,
    /** Lowercase-hex SHA-256 of the **uncompressed** APK; the server serves it gzipped. */
    val sha256: String,
    /** Uncompressed size, which is what the install session is sized against. */
    val size: Long,
    /** Compressed size, which is what actually crosses the network. */
    val gzSize: Long,
)

/** A package as this device should install it: one chosen variant, one chosen set of splits. */
data class GrapheneOSPackage(
    val packageName: String,
    val label: String,
    val description: String,
    val versionCode: Long,
    val versionName: String,
    /** Lowercase-hex SHA-256 digests of the certificates the APKs must be signed by. */
    val signers: List<String>,
    val iconUrl: String?,
    val apks: List<GrapheneOSApk>,
) {
    val totalSize: Long get() = apks.sumOf { it.size }
}

/**
 * The listing row for this package.
 *
 * No `apkUrl`: the download is a set of splits at index-derived URLs, not one file, so
 * `InstallCoordinator` reads the entry back out of the repository rather than being handed a
 * URL. `expectedSigners` and `apkSha256` are set for the detail page's benefit; the install
 * itself checks against every file's hash, not just the base APK's.
 */
fun GrapheneOSPackage.toUnifiedApp(): UnifiedApp = UnifiedApp(
    packageName = packageName,
    source = AppSource.GRAPHENEOS,
    name = label,
    description = description,
    summary = description.lineSequence().firstOrNull().orEmpty(),
    iconUrl = iconUrl,
    author = "Google LLC",
    versionName = versionName,
    versionCode = versionCode,
    sizeBytes = totalSize,
    containsSplit = apks.size > 1,
    repoUrl = GrapheneOSRepo.BASE_URL,
    expectedSigners = signers,
    apkSha256 = apks.firstOrNull { it.name == BASE_APK }?.sha256,
)

private const val BASE_APK = "base.apk"

/**
 * Reader for GrapheneOS's repository index.
 *
 * The index lists every published version of every package, for every SDK level, ABI, screen
 * density and language. Picking from it is the client's job, and getting it wrong means
 * either an install the device rejects or a download of hundreds of megabytes of splits it
 * will never use — so the selection rules here deliberately mirror GrapheneOS's own client
 * (`core/Repo.kt`): newest stable variant this SDK and ABI can run, then the base APK plus
 * only the ABI, density and language splits this device needs.
 *
 * Nothing here decides trust. The caller verifies the signature over the whole document
 * before parsing it, which is what makes the hashes and signer digests below worth reading.
 */
object GrapheneOSIndex {

    private const val STABLE = "stable"
    private const val CONFIG_SEPARATOR = "config."
    private const val APK_SUFFIX = ".apk"

    private val ABI_QUALIFIERS = mapOf(
        "armeabi-v7a" to "armeabi_v7a",
        "arm64-v8a" to "arm64_v8a",
        "x86" to "x86",
        "x86_64" to "x86_64",
    )

    private val DENSITY_QUALIFIERS = mapOf(
        "ldpi" to DisplayMetrics.DENSITY_LOW,
        "mdpi" to DisplayMetrics.DENSITY_MEDIUM,
        "tvdpi" to DisplayMetrics.DENSITY_TV,
        "hdpi" to DisplayMetrics.DENSITY_HIGH,
        "xhdpi" to DisplayMetrics.DENSITY_XHIGH,
        "xxhdpi" to DisplayMetrics.DENSITY_XXHIGH,
        "xxxhdpi" to DisplayMetrics.DENSITY_XXXHIGH,
    )

    /** Index timestamp, for the anti-rollback check. */
    fun timestamp(index: JSONObject): Long = index.optLong("time", 0L)

    /**
     * The subset of [wanted] this device can install, in [wanted]'s order.
     *
     * A package the index does not list, or lists with no variant this device can run, is
     * simply absent from the result rather than an error: the caller shows what it can offer.
     */
    fun packagesFor(
        context: Context,
        index: JSONObject,
        wanted: List<String>,
    ): List<GrapheneOSPackage> {
        val packages = index.optJSONObject("packages") ?: return emptyList()
        return wanted.mapNotNull { name ->
            packages.optJSONObject(name)?.let { parsePackage(context, name, it) }
        }
    }

    private fun parsePackage(
        context: Context,
        packageName: String,
        json: JSONObject,
    ): GrapheneOSPackage? {
        val variants = json.optJSONObject("variants") ?: return null
        val (versionCode, variant) = newestInstallableVariant(variants) ?: return null

        val apks = selectApks(context, variant)
        if (apks.isNullOrEmpty()) return null

        val signers = json.optJSONArray("signatures").toStringList().map { it.lowercase() }
        if (signers.isEmpty()) return null

        return GrapheneOSPackage(
            packageName = packageName,
            label = variant.optString("label", packageName),
            description = variant.opt("description") as? String
                ?: json.opt("description") as? String
                ?: "",
            versionCode = versionCode,
            versionName = variant.opt("versionName") as? String ?: versionCode.toString(),
            signers = signers,
            iconUrl = (json.opt("iconType") as? String)
                ?.let { GrapheneOSRepo.iconUrl(packageName, it) },
            apks = apks,
        )
    }

    /**
     * The newest stable variant this device can run, with its version code.
     *
     * Variants are keyed by version code and differ by SDK range and ABI, so several are
     * usually publishable at once and only some are installable here. An unrunnable variant
     * has to be skipped rather than clamped: installing a build outside its declared SDK
     * range is exactly what the range exists to prevent.
     */
    private fun newestInstallableVariant(variants: JSONObject): Pair<Long, JSONObject>? {
        var best: Pair<Long, JSONObject>? = null
        for (key in variants.keys()) {
            val versionCode = key.toLongOrNull() ?: continue
            val variant = variants.optJSONObject(key) ?: continue
            if (variant.optString("channel", STABLE) != STABLE) continue
            if (Build.VERSION.SDK_INT < variant.optInt("minSdk", 0)) continue
            if (Build.VERSION.SDK_INT > variant.optInt("maxSdk", Int.MAX_VALUE)) continue

            val abis = variant.optJSONArray("abis")?.toStringList()
            if (abis != null && DEVICE_ABI !in abis) continue

            if (best == null || versionCode > best.first) best = versionCode to variant
        }
        return best
    }

    /**
     * The files to download: the base APK plus the splits this device needs.
     *
     * Null when the four parallel arrays disagree on length, which would otherwise pair a
     * file name with another file's hash — a silent mis-verification rather than a failure.
     */
    private fun selectApks(context: Context, variant: JSONObject): List<GrapheneOSApk>? {
        val names = variant.optJSONArray("apks") ?: return null
        val hashes = variant.optJSONArray("apkHashes") ?: return null
        val sizes = variant.optJSONArray("apkSizes") ?: return null
        val gzSizes = variant.optJSONArray("apkGzSizes") ?: return null
        val count = names.length()
        if (hashes.length() != count || sizes.length() != count || gzSizes.length() != count) {
            return null
        }

        val languages = deviceLanguages(context)
        val kept = mutableListOf<GrapheneOSApk>()
        val byDensity = mutableMapOf<Int, MutableList<GrapheneOSApk>>()

        for (i in 0 until count) {
            val apk = GrapheneOSApk(
                name = names.optString(i),
                sha256 = hashes.optString(i).lowercase(),
                size = sizes.optLong(i),
                gzSize = gzSizes.optLong(i),
            )
            val qualifier = configQualifier(apk.name)
            val density = qualifier?.let { DENSITY_QUALIFIERS[it] }
            when {
                qualifier == null -> kept += apk
                density != null -> byDensity.getOrPut(density) { mutableListOf() }.add(apk)
                qualifier in ABI_QUALIFIERS.values ->
                    if (qualifier == DEVICE_ABI_QUALIFIER) kept += apk
                else -> if (qualifier in languages) kept += apk
            }
        }

        // One density split, not all of them: the closest at or above this screen, falling
        // back to the largest published when the screen is denser than anything on offer.
        if (byDensity.isNotEmpty()) {
            val densities = byDensity.keys.sorted()
            val target = context.resources.displayMetrics.densityDpi
            val chosen = densities.firstOrNull { it >= target } ?: densities.last()
            kept += byDensity.getValue(chosen)
        }
        return kept
    }

    /** The part after the last `config.`, or null for a file that is not a config split. */
    private fun configQualifier(name: String): String? {
        val index = name.lastIndexOf(CONFIG_SEPARATOR)
        if (index < 0 || !name.endsWith(APK_SUFFIX)) return null
        return name.substring(index + CONFIG_SEPARATOR.length, name.length - APK_SUFFIX.length)
    }

    private fun deviceLanguages(context: Context): Set<String> {
        val locales = context.resources.configuration.locales
        return (0 until locales.size()).mapTo(mutableSetOf()) { locales.get(it).language }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    /** Secondary ABIs are deliberately not considered; they run worse than the primary one. */
    private val DEVICE_ABI: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private val DEVICE_ABI_QUALIFIER: String? = ABI_QUALIFIERS[DEVICE_ABI]
}
