package com.pixelplus.owner

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.pixelplus.owner.security.LockPolicy
import com.pixelplus.owner.ui.text

class MainActivity : AppCompatActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    private lateinit var prefs: OwnerPrefs

    // the lock state lives here, outside the screens, so it survives their recomposition and is decided from the activity's lifecycle
    private var lock by mutableStateOf(LockPolicy.coldStart())
    private var credentialOpen = false
    private var wentToBackground = false
    private var pendingReauth: ((Boolean) -> Unit)? = null

    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        finishAuth(result.resultCode == RESULT_OK)
    }

    private fun lockApplies(): Boolean = prefs.lock().enabled && isSecure()

    private fun isSecure(): Boolean = deviceIsSecure(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = OwnerPrefs(this)
        if (!lockApplies()) lock = LockPolicy.onUnlockSucceeded(lock)
        setContent {
            Box {
                OwnerApp(prefs = prefs, reauthenticate = { done -> reauthenticate(done) }, onRequestNotifications = { askForNotifications() })
                if (lockApplies() && lock.contentHidden) {
                    MaterialTheme { LockScreen(secure = isSecure(), onUnlock = { requestUnlock() }) }
                }
            }
        }
        askForNotifications()
        FollowUpReminders.schedule(applicationContext)
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

    /** Unlocks the app. */
    private fun requestUnlock() {
        authenticate()
    }

    /** Asks for the owner again before a risky step (a restore). Not securable on a phone with no screen lock: then it just goes on. */
    private fun reauthenticate(done: (Boolean) -> Unit) {
        if (!isSecure()) { done(true); return }
        if (credentialOpen) { done(false); return }
        pendingReauth = done
        authenticate()
    }

    private fun finishAuth(ok: Boolean) {
        credentialOpen = false
        val reauth = pendingReauth
        pendingReauth = null
        if (reauth != null) {
            reauth(ok)
            return
        }
        lock = if (ok) LockPolicy.onUnlockSucceeded(lock) else LockPolicy.onUnlockFailed(lock)
    }

    /** The system prompt with fingerprint or face and the screen lock as a fallback; the plain screen-lock screen on phones that cannot combine them. */
    private fun authenticate() {
        if (credentialOpen) return
        val flags = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(flags) == BiometricManager.BIOMETRIC_SUCCESS) {
            credentialOpen = true
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finishAuth(true)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finishAuth(false)
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(text("brand_short"))
                .setSubtitle(text("biometric_subtitle"))
                .setAllowedAuthenticators(flags)
                .build()
            val started = runCatching { prompt.authenticate(info) }.isSuccess
            if (!started) finishAuth(false)
            return
        }
        val manager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val intent = manager?.createConfirmDeviceCredentialIntent(text("brand_short"), text("biometric_subtitle"))
        if (intent == null) {
            if (pendingReauth != null) finishAuth(false)
            return
        }
        credentialOpen = true
        runCatching { credential.launch(intent) }.onFailure { finishAuth(false) }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
