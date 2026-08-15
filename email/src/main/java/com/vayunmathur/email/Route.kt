package com.vayunmathur.email

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    object MessageList : Route
    @Serializable
    data class MessageThread(val accountEmail: String, val threadId: String) : Route
    @Serializable
    data class Composer(
        val to: String = "",
        val subject: String = "",
        val body: String = "",
        val inReplyTo: String? = null,
        val references: String? = null,
        val draftId: Long? = null
    ) : Route
    @Serializable
    object Outbox : Route
    @Serializable
    object Drafts : Route
    @Serializable
    object AddAccount : Route
    @Serializable
    object Settings : Route
    @Serializable
    data class EmlViewer(val uriString: String) : Route
}
