package com.vayunmathur.sdk.openassistant

class AssistantNotInstalledException :
    Exception("OpenAssistant app is not installed on this device")

class AssistantException(message: String) : Exception(message)
