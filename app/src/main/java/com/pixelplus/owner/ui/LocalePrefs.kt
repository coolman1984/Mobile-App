package com.pixelplus.owner.ui

/** Saved app language. Follow-system uses an empty locale list with AppCompat. */
enum class AppLanguage(val storageValue: String) {
    ARABIC("ar"),
    ENGLISH("en"),
    FOLLOW_SYSTEM("system"),
    ;

    companion object {
        fun fromStorage(raw: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == raw } ?: FOLLOW_SYSTEM
    }
}
