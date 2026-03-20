package com.achulkov.evomolior.util

class AppUtilImpl : AppUtil {

    override fun shareApp() {
        println("This is not supported on Web platform")
    }

    override fun openFeedbackMail() {
        println("This is not supported on Web platform")
    }

    override fun getAppName(): String {
        return "KAppMakerAllModules" //TODO Update name
    }

    override fun getAppVersionInfo(): String {
        return "1.0.0" //TODO Update version
    }

}
