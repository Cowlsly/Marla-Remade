package org.schabi.newpipe.extractor.services.youtube

internal object ClientsConstants {
    // Common client fields
    const val DESKTOP_CLIENT_PLATFORM = "DESKTOP"
    const val MOBILE_CLIENT_PLATFORM = "MOBILE"
    const val WATCH_CLIENT_SCREEN = "WATCH"
    const val EMBED_CLIENT_SCREEN = "EMBED"

    // WEB (YouTube desktop) client fields
    const val WEB_CLIENT_ID = "1"
    const val WEB_CLIENT_NAME = "WEB"
    /**
     * The client version for InnerTube requests with the WEB client, used as the last
     * fallback if the extraction of the real one failed.
     */
    const val WEB_HARDCODED_CLIENT_VERSION = "2.20260120.01.00"

    // WEB_REMIX (YouTube Music) client fields
    const val WEB_REMIX_CLIENT_ID = "67"
    const val WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
    const val WEB_REMIX_HARDCODED_CLIENT_VERSION = "1.20260121.03.00"

    // WEB_EMBEDDED_PLAYER (YouTube embeds)
    const val WEB_EMBEDDED_CLIENT_ID = "56"
    const val WEB_EMBEDDED_CLIENT_NAME = "WEB_EMBEDDED_PLAYER"
    const val WEB_EMBEDDED_CLIENT_VERSION = "1.20260122.01.00"

    // WEB_MUSIC_ANALYTICS (YouTube charts)
    const val WEB_MUSIC_ANALYTICS_CLIENT_ID = "31"
    const val WEB_MUSIC_ANALYTICS_CLIENT_NAME = "WEB_MUSIC_ANALYTICS"
    const val WEB_MUSIC_ANALYTICS_CLIENT_VERSION = "2.0"

    // IOS (iOS YouTube app) client fields
    const val IOS_CLIENT_ID = "5"
    const val IOS_CLIENT_NAME = "IOS"

    /**
     * The hardcoded client version of the iOS app used for InnerTube requests with this client.
     *
     * It can be extracted by getting the latest release version of the app on
     * [the App Store page of the YouTube app](https://apps.apple.com/us/app/youtube/id544007664), in the {@code What’s New} section.
     */
    const val IOS_CLIENT_VERSION = "21.03.2"

    /**
     * The device machine id for the iPhone 15 Pro Max, used to get 60fps with the iOS client.
     *
     * See [this GitHub Gist](https://gist.github.com/adamawolf/3048717) for more information.
     */
    const val IOS_DEVICE_MODEL = "iPhone16,2"

    /**
     * The iOS version to be used in JSON POST requests, the one of an iPhone 15 Pro Max running
     * iOS 18.2.1 with the hardcoded version of the iOS app (for the {@code "osVersion"} field).
     *
     * The value of this field seems to use the following structure:
     * "iOS major version.minor version.patch version.build version", where
     * "patch version" is equal to 0 if it isn't set
     * The build version corresponding to the iOS version used can be found on
     * [https://theapplewiki.com/wiki/Firmware/iPhone/18.x#iPhone_15_Pro_Max](https://theapplewiki.com/wiki/Firmware/iPhone/18.x#iPhone_15_Pro_Max)
     *
     * @see IOS_USER_AGENT_VERSION
     */
    const val IOS_OS_VERSION = "18.7.2.22H124"

    /**
     * The iOS version to be used in the HTTP user agent for requests.
     *
     * This should be the same of as [IOS_OS_VERSION].
     *
     * @see IOS_OS_VERSION
     */
    const val IOS_USER_AGENT_VERSION = "18_7_2"

    // ANDROID (Android YouTube app) client fields
    const val ANDROID_CLIENT_ID = "3"
    const val ANDROID_CLIENT_NAME = "ANDROID"

    /**
     * The hardcoded client version of the Android app used for InnerTube requests with this client.
     *
     * It can be extracted by getting the latest release version of the app in an APK repository
     * such as [APKMirror](https://www.apkmirror.com/apk/google-inc/youtube/).
     */
    const val ANDROID_CLIENT_VERSION = "21.03.36"

    // ANDROID_VR (Meta Quest YouTube VR app) client fields.
    // This client still returns direct stream URLs without a PO Token, so it is used as the
    // primary playback client while SABR (WEB/visitorData) is being probabilistically rejected.
    const val ANDROID_VR_CLIENT_ID = "28"
    const val ANDROID_VR_CLIENT_NAME = "ANDROID_VR"
    const val ANDROID_VR_CLIENT_VERSION = "1.65.10"
    const val ANDROID_VR_DEVICE_MAKE = "Oculus"
    const val ANDROID_VR_DEVICE_MODEL = "Quest 3"
    const val ANDROID_VR_OS_NAME = "Android"
    const val ANDROID_VR_OS_VERSION = "12L"
    const val ANDROID_VR_SDK_VERSION = 32
    const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    // visionOS client fields
    const val VISIONOS_CLIENT_ID = "101"
    const val VISIONOS_CLIENT_NAME = "VISIONOS"
    const val VISIONOS_CLIENT_VERSION = "1.02"
    const val VISIONOS_DEVICE_MODEL = "RealityDevice14,1"
    const val VISIONOS_VERSION = "25.6.0.23O471"
    const val VISIONOS_USER_AGENT_VERSION = "25_6_0"
}
