package com.eworldq8.soor

import com.eworldq8.soor.engine.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

// Soor follows the phone: the first of the phone's languages it speaks decides,
// and a phone in any other language gets English.
class AppLanguageTest {

    @Test
    fun arabicPhone() {
        assertEquals(Lang.AR, AppLanguage.pick(listOf("ar-KW")))
        assertEquals(Lang.AR, AppLanguage.pick(listOf("ar")))
        assertEquals(Lang.AR, AppLanguage.pick(listOf("ar_SA")))
    }

    @Test
    fun englishPhone() {
        assertEquals(Lang.EN, AppLanguage.pick(listOf("en-US")))
        assertEquals(Lang.EN, AppLanguage.pick(listOf("en-GB", "ar-KW")))
    }

    @Test
    fun theFirstLanguageSoorSpeaksDecides() {
        // a French phone with Arabic second follows the Arabic, as Android itself would
        assertEquals(Lang.AR, AppLanguage.pick(listOf("fr-FR", "ar-KW")))
        assertEquals(Lang.EN, AppLanguage.pick(listOf("hi-IN", "en-IN", "ar")))
    }

    @Test
    fun anyOtherLanguageGetsEnglish() {
        assertEquals(Lang.EN, AppLanguage.pick(listOf("hi-IN")))
        assertEquals(Lang.EN, AppLanguage.pick(listOf("fil-PH", "ur-PK")))
        assertEquals(Lang.EN, AppLanguage.pick(emptyList()))
    }
}
