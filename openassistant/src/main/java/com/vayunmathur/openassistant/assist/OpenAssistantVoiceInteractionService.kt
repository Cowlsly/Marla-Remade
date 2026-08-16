package com.vayunmathur.openassistant.assist

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Entry point that registers OpenAssistant as a system voice interaction (digital
 * assistant) provider. It does almost nothing itself: the platform binds this
 * service to discover the app as an assistant, then routes each assist gesture to
 * [OpenAssistantSessionService] (declared in @xml/voice_interaction_service), which
 * spawns an [OpenAssistantSession] to actually receive the screen's AssistStructure.
 */
class OpenAssistantVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService ready")
    }

    companion object {
        private const val TAG = "OAVoiceInteraction"
    }
}
