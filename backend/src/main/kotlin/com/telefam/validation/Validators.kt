package com.telefam.validation

private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,30}$")
private val PHONE_REGEX = Regex("^[0-9]{4,15}$")
private val CODE_REGEX = Regex("^[0-9]{6}$")

object Validators {
    fun isValidEmail(email: String) = email.length <= 255 && EMAIL_REGEX.matches(email)

    fun isValidPassword(password: String): Boolean =
        password.length >= 8 && password.any { it.isDigit() } && password.any { it.isLetter() }

    fun isValidUsername(username: String) = USERNAME_REGEX.matches(username.lowercase())

    fun isValidFullName(name: String) = name.isNotBlank() && name.length <= 100 &&
        name.all { it.isLetter() || it.isWhitespace() || it == '-' || it == '\'' }

    fun isValidPhone(phone: String) = PHONE_REGEX.matches(phone)

    fun isValidCountryDialCode(code: String) = Regex("^\\+[0-9]{1,4}$").matches(code)

    fun isValidGender(gender: String) = gender in setOf("male", "female", "non_binary", "prefer_not_to_say")

    fun isValidBio(bio: String) = bio.length <= 150

    /** Public profile website: optional http(s) URL, sane length, no whitespace/control chars. */
    fun isValidWebsite(url: String): Boolean {
        if (url.length > 255 || url.any { it.isWhitespace() || it.isISOControl() }) return false
        return try {
            val uri = java.net.URI(url)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        } catch (e: Exception) { false }
    }

    fun isValidLocationName(name: String) = name.length <= 120 && name.none { it.isISOControl() }

    fun isValidOtpCode(code: String) = CODE_REGEX.matches(code)

    fun isValidDob(isoDate: String): Boolean = try {
        val dob = java.time.LocalDate.parse(isoDate)
        val age = java.time.Period.between(dob, java.time.LocalDate.now()).years
        age in 13..120
    } catch (e: Exception) { false }
}
