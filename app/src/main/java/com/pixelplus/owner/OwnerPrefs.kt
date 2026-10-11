package com.pixelplus.owner

import android.content.Context
import com.pixelplus.owner.ui.theme.AppearancePrefs
import com.pixelplus.owner.ui.theme.LockPrefs
import com.pixelplus.owner.ui.theme.PrefsCodec

/** Non-secret preferences (appearance, lock timeout). Hub tokens are never here; they stay in [SecureHubStore]. */
class OwnerPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("owner_prefs", Context.MODE_PRIVATE)

    fun appearance(): AppearancePrefs =
        PrefsCodec.appearanceFrom(listOf("mode", "palette", "dynamic", "arFont", "enFont", "scale", "motion").associateWith { sp.getString("ap_$it", null) })

    fun saveAppearance(p: AppearancePrefs) {
        val edit = sp.edit()
        PrefsCodec.appearanceToMap(p).forEach { (k, v) -> edit.putString("ap_$k", v) }
        edit.apply()
    }

    fun lock(): LockPrefs = PrefsCodec.lockFrom(sp.getString("lock_enabled", null), sp.getString("lock_timeout", null))

    fun saveLock(p: LockPrefs) {
        sp.edit().putString("lock_enabled", p.enabled.toString()).putString("lock_timeout", p.timeout.name).apply()
    }
}
