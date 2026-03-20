package com.achulkov.evomolior.domain.exceptions

class UserAlreadyExistsException :
    Exception("Looks like you already have an account with this email. Please, try again signing in")