package org.schabi.newpipe.extractor.services.youtube

import javax.annotation.Nonnull
import javax.annotation.Nullable

class InnertubeClientRequestInfo private constructor(
    @JvmField
    @field:Nonnull
    var clientInfo: ClientInfo,
    @JvmField
    @field:Nonnull
    var deviceInfo: DeviceInfo
) {

    class ClientInfo private constructor(
        @JvmField
        @field:Nonnull
        var clientName: String,
        @JvmField
        @field:Nonnull
        var clientVersion: String,
        @JvmField
        @field:Nonnull
        var clientId: String,
        @JvmField
        @field:Nullable
        var clientScreen: String?,
        @JvmField
        @field:Nullable
        var visitorData: String?,
        @JvmField
        @field:Nullable
        var userAgent: String?,
        @JvmField
        @field:Nullable
        var timeZone: String?
    ) {
        companion object {
            internal fun create(
                clientName: String,
                clientVersion: String,
                clientId: String,
                clientScreen: String?,
                visitorData: String?,
                userAgent: String? = null,
                timeZone: String? = null
            ): ClientInfo = ClientInfo(
                clientName, clientVersion, clientId, clientScreen, visitorData, userAgent, timeZone
            )
        }
    }

    class DeviceInfo private constructor(
        @JvmField
        @field:Nullable
        var platform: String?,
        @JvmField
        @field:Nullable
        var deviceMake: String?,
        @JvmField
        @field:Nullable
        var deviceModel: String?,
        @JvmField
        @field:Nullable
        var osName: String?,
        @JvmField
        @field:Nullable
        var osVersion: String?,
        @JvmField
        var androidSdkVersion: Int
    ) {
        companion object {
            internal fun create(
                platform: String?,
                deviceMake: String?,
                deviceModel: String?,
                osName: String?,
                osVersion: String?,
                androidSdkVersion: Int
            ): DeviceInfo = DeviceInfo(platform, deviceMake, deviceModel, osName, osVersion, androidSdkVersion)
        }
    }

    companion object {
        @JvmStatic
        fun ofWebClient(): InnertubeClientRequestInfo {
            return InnertubeClientRequestInfo(
                ClientInfo.create(
                    ClientsConstants.WEB_CLIENT_NAME,
                    ClientsConstants.WEB_HARDCODED_CLIENT_VERSION,
                    ClientsConstants.WEB_CLIENT_ID,
                    ClientsConstants.WATCH_CLIENT_SCREEN,
                    null
                ),
                DeviceInfo.create(
                    ClientsConstants.DESKTOP_CLIENT_PLATFORM,
                    null, null, null, null, -1
                )
            )
        }

        @JvmStatic
        fun ofWebEmbeddedPlayerClient(): InnertubeClientRequestInfo {
            return InnertubeClientRequestInfo(
                ClientInfo.create(
                    ClientsConstants.WEB_EMBEDDED_CLIENT_NAME,
                    ClientsConstants.WEB_EMBEDDED_CLIENT_VERSION,
                    ClientsConstants.WEB_EMBEDDED_CLIENT_ID,
                    ClientsConstants.EMBED_CLIENT_SCREEN,
                    null
                ),
                DeviceInfo.create(
                    ClientsConstants.DESKTOP_CLIENT_PLATFORM,
                    null, null, null, null, -1
                )
            )
        }

        @JvmStatic
        fun ofWebMusicAnalyticsChartsClient(): InnertubeClientRequestInfo {
            return InnertubeClientRequestInfo(
                ClientInfo.create(
                    ClientsConstants.WEB_MUSIC_ANALYTICS_CLIENT_NAME,
                    ClientsConstants.WEB_MUSIC_ANALYTICS_CLIENT_VERSION,
                    ClientsConstants.WEB_MUSIC_ANALYTICS_CLIENT_ID,
                    null,
                    null
                ),
                DeviceInfo.create(null, null, null, null, null, -1)
            )
        }

        @JvmStatic
        fun ofAndroidClient(): InnertubeClientRequestInfo {
            return InnertubeClientRequestInfo(
                ClientInfo.create(
                    ClientsConstants.ANDROID_CLIENT_NAME,
                    ClientsConstants.ANDROID_CLIENT_VERSION,
                    ClientsConstants.ANDROID_CLIENT_ID,
                    ClientsConstants.WATCH_CLIENT_SCREEN,
                    null
                ),
                DeviceInfo.create(
                    ClientsConstants.MOBILE_CLIENT_PLATFORM,
                    null, null,
                    "Android", "16", 36
                )
            )
        }

        @JvmStatic
        fun ofAndroidVrClient(): InnertubeClientRequestInfo {
            // Mirrors PipePipe's android_vr player request context.client: it carries the client
            // userAgent and timeZone=UTC, and does NOT send a platform field.
            return InnertubeClientRequestInfo(
                ClientInfo.create(
                    ClientsConstants.ANDROID_VR_CLIENT_NAME,
                    ClientsConstants.ANDROID_VR_CLIENT_VERSION,
                    ClientsConstants.ANDROID_VR_CLIENT_ID,
                    null,
                    null,
                    ClientsConstants.ANDROID_VR_USER_AGENT,
                    "UTC"
                ),
                DeviceInfo.create(
                    null,
                    ClientsConstants.ANDROID_VR_DEVICE_MAKE,
                    ClientsConstants.ANDROID_VR_DEVICE_MODEL,
                    ClientsConstants.ANDROID_VR_OS_NAME,
                    ClientsConstants.ANDROID_VR_OS_VERSION,
                    ClientsConstants.ANDROID_VR_SDK_VERSION
                )
            )
        }

        @JvmStatic
        fun ofIosClient(): InnertubeClientRequestInfo {
            return InnertubeClientRequestInfo(
                ClientInfo.create(
                    ClientsConstants.IOS_CLIENT_NAME,
                    ClientsConstants.IOS_CLIENT_VERSION,
                    ClientsConstants.IOS_CLIENT_ID,
                    ClientsConstants.WATCH_CLIENT_SCREEN,
                    null
                ),
                DeviceInfo.create(
                    ClientsConstants.MOBILE_CLIENT_PLATFORM,
                    "Apple",
                    ClientsConstants.IOS_DEVICE_MODEL,
                    "iOS",
                    ClientsConstants.IOS_OS_VERSION,
                    -1
                )
            )
        }

        @JvmStatic
        fun ofVisionOsClient(): InnertubeClientRequestInfo {
            return InnertubeClientRequestInfo(
                ClientInfo.create(
                    ClientsConstants.VISIONOS_CLIENT_NAME,
                    ClientsConstants.VISIONOS_CLIENT_VERSION,
                    ClientsConstants.VISIONOS_CLIENT_ID,
                    ClientsConstants.WATCH_CLIENT_SCREEN,
                    null
                ),
                DeviceInfo.create(
                    ClientsConstants.MOBILE_CLIENT_PLATFORM,
                    "Apple",
                    ClientsConstants.VISIONOS_DEVICE_MODEL,
                    "visionOS",
                    ClientsConstants.VISIONOS_VERSION,
                    -1
                )
            )
        }
    }
}
