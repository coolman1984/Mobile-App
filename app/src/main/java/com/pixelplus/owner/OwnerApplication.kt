package com.pixelplus.owner

import android.app.Application
import com.pixelplus.owner.ui.LocaleStore

class OwnerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleStore.warmStart(this)
    }
}
