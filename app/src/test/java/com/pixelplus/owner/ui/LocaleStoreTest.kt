package com.pixelplus.owner.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleStoreTest {
    @Test fun storageRoundTrip() {
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromStorage("ar"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromStorage("en"))
        assertEquals(AppLanguage.FOLLOW_SYSTEM, AppLanguage.fromStorage("system"))
        assertEquals(AppLanguage.FOLLOW_SYSTEM, AppLanguage.fromStorage(null))
        assertEquals(AppLanguage.FOLLOW_SYSTEM, AppLanguage.fromStorage("unknown"))
    }
}
