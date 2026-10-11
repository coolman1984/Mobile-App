package com.pixelplus.owner

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.pixelplus.owner.security.LockPolicy
import com.pixelplus.owner.security.LockState

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    private lateinit var prefs: OwnerPrefs

    // the lock state lives here, outside the screens, so it survives their recomposition and is decided from the activity's lifecycle
    private var lock by mutableStateOf(LockPolicy.coldStart())
    private var credentialOpen = false
    private var wentToBackground = false

    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        credentialOpen = false
        lock = if (result.resultCode == RESULT_OK) LockPolicy.onUnlockSucceeded(lock) else LockPolicy.onUnlockFailed(lock)
    }

    private fun lockApplies(): Boolean = prefs.lock().enabled && isSecure()

    private fun isSecure(): Boolean = deviceIsSecure(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = OwnerPrefs(this)
        if (!lockApplies()) lock = LockPolicy.onUnlockSucceeded(lock)
        setContent {
            Box {
                OwnerApp(prefs = prefs, onRequestNotifications = { askForNotifications() })
                if (lockApplies() && lock.contentHidden) {
                    MaterialTheme { LockScreen(secure = isSecure(), onUnlock = { requestUnlock() }) }
                }
            }
        }
        askForNotifications()
    }

    override fun onPause() {
        super.onPause()
        if (credentialOpen) return
        // hide first: the recents thumbnail is taken after this point
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        wentToBackground = true
        if (lockApplies()) lock = LockPolicy.onBackground(lock, SystemClock.elapsedRealtime())
    }

    override fun onResume() {
        super.onResume()
        if (credentialOpen) return
        if (!lockApplies()) {
            lock = LockPolicy.onUnlockSucceeded(lock)
        } else if (wentToBackground) {
            lock = LockPolicy.onForeground(lock, SystemClock.elapsedRealtime(), prefs.lock().timeout)
        }
        wentToBackground = false
        if (lock.locked) {
            requestUnlock()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun requestUnlock() {
        if (credentialOpen) return
        val manager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager ?: return
        val intent = manager.createConfirmDeviceCredentialIntent("بيكسل بلس", "افتح القفل للمتابعة / Unlock to continue") ?: return
        credentialOpen = true
        runCatching { credential.launch(intent) }.onFailure { credentialOpen = false }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
