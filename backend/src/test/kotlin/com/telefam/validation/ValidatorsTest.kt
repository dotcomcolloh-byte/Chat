package com.telefam.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidatorsTest {

    @Test fun `valid emails are accepted`() {
        assertTrue(Validators.isValidEmail("a@b.co"))
        assertTrue(Validators.isValidEmail("first.last+tag@sub.example.com"))
    }

    @Test fun `invalid emails are rejected`() {
        assertFalse(Validators.isValidEmail(""))
        assertFalse(Validators.isValidEmail("no-at-sign.com"))
        assertFalse(Validators.isValidEmail("a@b"))
        assertFalse(Validators.isValidEmail("a b@c.com"))
        assertFalse(Validators.isValidEmail("a".repeat(250) + "@x.com")) // > 255 chars
    }

    @Test fun `password needs 8 chars with a letter and a digit`() {
        assertTrue(Validators.isValidPassword("Passw0rd1"))
        assertFalse(Validators.isValidPassword("short1"))
        assertFalse(Validators.isValidPassword("allletters"))
        assertFalse(Validators.isValidPassword("12345678"))
    }

    @Test fun `otp code must be exactly six digits`() {
        assertTrue(Validators.isValidOtpCode("012345"))
        assertFalse(Validators.isValidOtpCode("12345"))
        assertFalse(Validators.isValidOtpCode("1234567"))
        assertFalse(Validators.isValidOtpCode("12345a"))
    }

    @Test fun `username rules`() {
        assertTrue(Validators.isValidUsername("tele_fam1"))
        assertTrue(Validators.isValidUsername("ABC")) // case-insensitive
        assertFalse(Validators.isValidUsername("ab"))
        assertFalse(Validators.isValidUsername("has space"))
        assertFalse(Validators.isValidUsername("a".repeat(31)))
    }

    @Test fun `date of birth enforces the 13 to 120 age window`() {
        val today = java.time.LocalDate.now()
        assertTrue(Validators.isValidDob(today.minusYears(30).toString()))
        assertFalse(Validators.isValidDob(today.minusYears(12).toString()))
        assertFalse(Validators.isValidDob(today.minusYears(121).toString()))
        assertFalse(Validators.isValidDob("not-a-date"))
    }

    @Test fun `phone and dial code`() {
        assertTrue(Validators.isValidPhone("0712345678"))
        assertFalse(Validators.isValidPhone("123"))
        assertEquals(true, Validators.isValidCountryDialCode("+254"))
        assertEquals(false, Validators.isValidCountryDialCode("254"))
    }
}
