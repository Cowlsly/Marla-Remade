package com.vayunmathur.games.voxels.util

import android.content.Context
import android.content.res.AssetManager
import android.media.MediaPlayer

// Background music manager. Cycles the track playlist ambiently at a low volume; a jukebox disc
// temporarily takes over (pausing ambient) until stopped. Best-effort; never throws into the UI.
object MusicFx {
    private var ambient: MediaPlayer? = null
    private var disc: MediaPlayer? = null
    private var currentDisc: String? = null
    private var assets: AssetManager? = null
    private var idx = 0
    private val playlist = listOf(
        "mcl_forest.ogg", "mcl_piano.ogg", "mcl_winter.ogg", "mcl_gift.ogg",
        "golden.ogg", "mcl_mining.ogg", "lullaby.ogg"
    )

    private fun make(assets: AssetManager, asset: String, loop: Boolean, vol: Float, onDone: (() -> Unit)?): MediaPlayer? {
        return try {
            val afd = assets.openFd("music/$asset")
            val p = MediaPlayer()
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            p.isLooping = loop
            p.setVolume(vol, vol)
            p.setOnPreparedListener { it.start() }
            p.setOnErrorListener { _, _, _ -> true }
            if (onDone != null) p.setOnCompletionListener { onDone() }
            p.prepareAsync()
            p
        } catch (_: Throwable) { null }
    }

    fun startAmbient(c: Context) {
        assets = c.applicationContext.assets
        if (ambient == null && disc == null) playNextAmbient()
    }

    private fun playNextAmbient() {
        val a = assets ?: return
        val asset = playlist[idx % playlist.size]
        idx++
        ambient?.let { try { it.release() } catch (_: Throwable) {} }
        ambient = make(a, asset, loop = false, vol = 0.45f, onDone = { playNextAmbient() })
    }

    // Jukebox: same disc again -> stop and resume ambient; a new disc -> take over; null -> resume.
    fun toggle(c: Context, asset: String?) {
        if (asset == null || asset == currentDisc) { stopDisc(); resumeAmbient(); return }
        stopDisc()
        try { ambient?.pause() } catch (_: Throwable) {}
        disc = make(c.applicationContext.assets, asset, loop = true, vol = 0.8f, onDone = null)
        currentDisc = asset
    }

    private fun stopDisc() {
        disc?.let { try { it.release() } catch (_: Throwable) {} }
        disc = null; currentDisc = null
    }

    private fun resumeAmbient() {
        val a = ambient
        if (a != null) { try { a.start() } catch (_: Throwable) { playNextAmbient() } } else playNextAmbient()
    }

    fun stop() { stopDisc(); ambient?.let { try { it.release() } catch (_: Throwable) {} }; ambient = null }
}
