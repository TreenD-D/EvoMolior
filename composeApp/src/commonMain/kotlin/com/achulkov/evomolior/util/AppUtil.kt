package com.achulkov.evomolior.util

interface AppUtil {
    fun getAppName():String
    fun shareApp()
    fun openFeedbackMail()
    fun getAppVersionInfo(): String
}