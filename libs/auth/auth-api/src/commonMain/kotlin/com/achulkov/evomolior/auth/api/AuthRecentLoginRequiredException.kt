package com.achulkov.evomolior.auth.api

class AuthRecentLoginRequiredException : Exception("You need to login again to perform this action")