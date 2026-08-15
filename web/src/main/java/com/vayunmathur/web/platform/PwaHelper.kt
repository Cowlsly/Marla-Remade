package com.vayunmathur.web.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.ImageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PwaInfo(
    val name: String,
    val shortName: String = "",
    val iconUrl: String? = null,
    val faviconUrl: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null,
    val displayMode: String = "standalone",
    val startUrl: String? = null,
    val origin: String,
    val manifestUrl: String? = null,
    val hasManifest: Boolean = false,
)

object PwaHelper {
    private const val TAG = "PwaHelper"

    // JS probe - intentionally avoids Kotlin ${} templates by using string concatenation only.
    const val MANIFEST_PROBE_JS = "(function(){" +
            "try{" +
            "var origin=location.origin;" +
            "var title='';" +
            "try{var og=document.querySelector('meta[property=\"og:title\"]');title=(og&&og.content)?og.content.trim():(document.title||'').trim();}catch(e){title=document.title||'';}" +
            "var iconUrl=null,faviconUrl=null,themeColor=null,bgColor=null,displayMode='browser',startUrl=location.href,manifestUrl=null,hasManifest=false;" +
            "try{var tm=document.querySelector('meta[name=\"theme-color\"]');if(tm)themeColor=tm.content||null;}catch(e){}" +
            "try{var ms=document.querySelector('meta[name=\"msapplication-TileColor\"]');if(ms)bgColor=ms.content||null;}catch(e){}" +
            "try{var ml=document.querySelector('link[rel=\"manifest\"]');if(ml){manifestUrl=ml.href;hasManifest=!!manifestUrl;}}catch(e){}" +
            "function bestIcon(){" +
            "try{" +
            "var cands=[];var ls=document.querySelectorAll('link[rel*=\"icon\"]');" +
            "for(var i=0;i<ls.length;i++){var l=ls[i];var href=l.href;if(!href)continue;var rel=(l.rel||'').toLowerCase();var sizes=(l.getAttribute('sizes')||'').toLowerCase();var score=0;" +
            "if(rel.indexOf('apple-touch-icon')>=0)score=90;if(rel==='icon'||rel.indexOf('icon')>=0)score=Math.max(score,70);" +
            "if(sizes.indexOf('192')>=0||sizes.indexOf('512')>=0)score+=40;if(sizes.indexOf('180')>=0)score+=20;" +
            "if(href.indexOf('192')>=0||href.indexOf('512')>=0)score+=25;if(href.endsWith('.png'))score+=10;" +
            "cands.push({href:href,score:score});}" +
            "if(cands.length===0)return null;cands.sort(function(a,b){return b.score-a.score;});return cands[0].href;" +
            "}catch(e){return null;}}" +
            "try{var il=document.querySelector('link[rel=\"icon\"], link[rel=\"shortcut icon\"]');if(il)faviconUrl=il.href||null;}catch(e){}" +
            "var best=bestIcon();if(best)iconUrl=best;if(!iconUrl&&faviconUrl)iconUrl=faviconUrl;" +
            "return JSON.stringify({title:title,name:'',shortName:'',iconUrl:iconUrl,faviconUrl:faviconUrl,themeColor:themeColor,bgColor:bgColor,displayMode:displayMode,startUrl:startUrl,manifestUrl:manifestUrl,hasManifest:hasManifest,origin:origin});" +
            "}catch(e){try{return JSON.stringify({title:document.title||'',origin:location.origin||''});}catch(e2){return null;}}})();"

    fun parseProbeJson(escapedJson: String?): PwaInfo? {
        if (escapedJson.isNullOrBlank()) return null
        var s = escapedJson.trim()
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
        }
        if (s.isBlank() || s == "null") return null
        fun strField(key: String): String? {
            val m = Regex("\"" + key + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"").find(s) ?: return null
            return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
        }
        val title = strField("title") ?: ""
        val name = strField("name")?.ifBlank { null } ?: title
        val shortName = strField("shortName") ?: ""
        val iconUrl = strField("iconUrl")
        val faviconUrl = strField("faviconUrl")
        val themeColor = strField("themeColor")
        val bgColor = strField("bgColor")
        val displayMode = strField("displayMode") ?: "browser"
        val startUrl = strField("startUrl")
        val manifestUrl = strField("manifestUrl")
        val origin = strField("origin") ?: ""
        val hasManifest = Regex("\"hasManifest\"\\s*:\\s*(true|false)").find(s)?.groupValues?.get(1) == "true"
        if (origin.isBlank() && name.isBlank() && title.isBlank() && iconUrl == null) return null
        return PwaInfo(
            name = name.ifBlank { title },
            shortName = shortName,
            iconUrl = iconUrl,
            faviconUrl = faviconUrl,
            themeColor = themeColor,
            backgroundColor = bgColor,
            displayMode = displayMode,
            startUrl = startUrl,
            origin = origin.ifBlank { startUrl ?: "" },
            manifestUrl = manifestUrl,
            hasManifest = hasManifest,
        )
    }

    fun displayTitle(info: PwaInfo?, fallbackTitle: String, fallbackUrl: String): String {
        val n = info?.name?.ifBlank { null } ?: info?.shortName?.ifBlank { null }
        val t = fallbackTitle.ifBlank { null }
        val host = runCatching { BrowserUtils.hostFromUrl(fallbackUrl) }.getOrNull()?.takeIf { it.isNotBlank() }
        return (n ?: t ?: host ?: "Site").take(48)
    }

    fun isPinSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun shortcutId(url: String): String = try {
        val origin = BrowserUtils.originFromUrl(url)
        "pwa_" + origin.hashCode() + "_" + url.hashCode()
    } catch (_: Exception) {
        "pwa_" + url.hashCode()
    }

    fun createPwaIntent(context: Context, url: String, title: String?): Intent {
        return Intent(context, com.vayunmathur.web.platform.PwaActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra(com.vayunmathur.web.platform.PwaActivity.EXTRA_URL, url)
            putExtra(com.vayunmathur.web.platform.PwaActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    suspend fun loadIconBitmap(context: Context, iconUrl: String?): Bitmap? {
        if (iconUrl.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val loader = ImageLoader.get(context)
                val req = ImageRequest.Builder(context)
                    .data(iconUrl)
                    .allowHardware(false)
                    .size(192)
                    .build()
                val result = loader.execute(req)
                val bitmap = (result as? ImageResult.Success)?.bitmap ?: return@withContext null
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            } catch (e: Exception) {
                Log.w(TAG, "loadIconBitmap failed " + iconUrl, e)
                null
            }
        }
    }

    fun textIconBitmap(title: String, sizePx: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hash = title.hashCode()
        val rr = 80 + (hash shr 16 and 0x7F)
        val gg = 80 + (hash shr 8 and 0x7F)
        val bb = 80 + (hash and 0x7F)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(rr, gg, bb)
        }
        val radius = sizePx * 0.28f
        canvas.drawRoundRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), radius, radius, bg)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = sizePx * 0.52f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val letter = title.trim().firstOrNull()?.uppercase() ?: "W"
        val x = sizePx / 2f
        val y = sizePx / 2f - (tp.descent() + tp.ascent()) / 2f
        canvas.drawText(letter, x, y, tp)
        return bmp
    }

    suspend fun requestPinShortcut(
        context: Context,
        url: String,
        title: String,
        iconUrl: String?,
        faviconUrl: String? = null,
    ): Boolean {
        if (!isPinSupported(context)) return false
        return withContext(Dispatchers.Main) {
            try {
                val id = shortcutId(url)
                val intent = createPwaIntent(context, url, title)
                val shortLabel = title.takeIf { it.isNotBlank() }?.take(10) ?: BrowserUtils.hostFromUrl(url).take(10)
                val longLabel = title.ifBlank { url }.take(48)

                var bmp = loadIconBitmap(context, iconUrl)
                if (bmp == null && !faviconUrl.isNullOrBlank()) bmp = loadIconBitmap(context, faviconUrl)
                if (bmp == null) {
                    try {
                        val origin = BrowserUtils.originFromUrl(url)
                        bmp = loadIconBitmap(context, origin + "/favicon.ico")
                    } catch (_: Exception) {}
                }
                val finalBmp = bmp ?: textIconBitmap(title)
                val icon = IconCompat.createWithAdaptiveBitmap(finalBmp)

                val info = ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(shortLabel)
                    .setLongLabel(longLabel)
                    .setIntent(intent)
                    .setIcon(icon)
                    .build()

                ShortcutManagerCompat.requestPinShortcut(context, info, null)
            } catch (e: Exception) {
                Log.e(TAG, "requestPinShortcut failed", e)
                false
            }
        }
    }
}
