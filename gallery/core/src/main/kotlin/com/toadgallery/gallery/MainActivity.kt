package com.toadgallery.gallery

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.toadgallery.gallery.ui.ToadGalleryApp
import com.toadgallery.gallery.ui.vault.VaultUiState
import com.toadgallery.gallery.ui.vault.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * toadGALLERY product shell (roadmap Phase 2, ADR-0009).
 *
 * Compose-native single-activity shell: `setContent` hosts the Material 3 app with
 * a `NavHost` for the four top-level destinations (Photos, Albums, Search, Settings).
 * Replaces the superseded ADR-0005 View-based + `org.fossify:commons` shell
 * (`BaseSimpleActivity` + Fragments + XML layouts).
 *
 * Edge-to-edge contract (issue #11, docs/architecture/design-tokens.md §4):
 * [enableEdgeToEdge] runs before content attaches, so the very first frame
 * already draws behind transparent system bars --- no scrim flash and no
 * post-attach relayout ("no layout jumps"). It also transparents the
 * navigation bar, disables nav-bar contrast enforcement on API 29+, and stops
 * fitting system windows. Inset consumption happens exactly once, in Compose:
 * the Material 3 `Scaffold` in `ToadGalleryApp` turns `contentWindowInsets`
 * into `innerPadding` for the `NavHost`, so screens and the full-screen
 * states (permission, loading, error, unsupported) center inside inset-paid
 * geometry and never pad themselves against the status bar.
 *
 * Issue #57 (ADR-0019): the base class is [FragmentActivity] (the direct
 * supertype of `AppCompatActivity`; everything else about the shell is
 * unchanged) because the androidx `BiometricPrompt` used by the hidden
 * vault's biometric unlock binding requires a FragmentActivity host ---
 * `ComponentActivity` alone is not enough for its prompt fragment. The app
 * still uses no Fragments of its own; Compose remains the only UI toolkit
 * (ADR-0009).
 *
 * ## Recents-screenshot protection (audit D-2, r51)
 * While the hidden vault's session is unlocked ([VaultUiState.Unlocked] ---
 * PIN or biometric alike), this window holds `FLAG_SECURE`: the OS recents
 * app-switcher snapshot of decrypted vault content is a plaintext image
 * persisting in system UI storage for as long as the session lives, which
 * broke the vault's "plaintext never persists outside memory" design goal.
 * The flag is cleared the moment the state is anything else, so ordinary
 * gallery use keeps normal screenshot behavior.
 *
 * The collector runs while the activity is RESUMED and is cancelled at
 * ON_PAUSE --- deliberately, and this ordering is load-bearing: the vault's
 * auto-lock (ADR-0017) fires at ON_STOP, right around when the system takes
 * the recents snapshot. Cancelling at ON_PAUSE means the flag is still HELD
 * while that snapshot is taken (blank recents card --- the safe direction),
 * and the clear lands only after the next resume of a locked session. A
 * collector that stayed live through ON_STOP would race the snapshot and
 * could un-secure the window with the last drawn frame still showing
 * decrypted thumbnails.
 *
 * `setRecentsScreenshotEnabled(false)` (API 33+) is deliberately NOT used:
 * `FLAG_SECURE` already blanks the recents thumbnail on every API level,
 * the API-33 call would be a redundant, version-guarded branch, and the
 * flag additionally blocks manual screenshots of the unlocked vault surface
 * (desired: decrypted thumbnails are on screen).
 *
 * The observation idiom mirrors the shell's existing ViewModel-to-lifecycle
 * bridge (the `LifecycleEventObserver` the Compose shell registers for the
 * vault auto-lock in `ToadGalleryApp`) rather than introducing
 * `repeatOnLifecycle`, which nothing else in this codebase uses.
 */
class MainActivity : FragmentActivity() {

    /**
     * The D-2 collector's scope: a supervisor on the main dispatcher, so a
     * failed collection can never cancel a sibling and the flag updates land
     * on the thread window flags belong to. Cancelled wholesale at ON_DESTROY.
     */
    private val vaultWindowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeVaultWindowSecurity()
        setContent {
            ToadGalleryApp()
        }
    }

    /**
     * One observation site for D-2: collect the vault's state flow while
     * RESUMED (see the class KDoc for why the RESUME->PAUSE window is the
     * load-bearing choice) and hold/clear `FLAG_SECURE` on this window
     * accordingly. The ViewModel is the SAME activity-scoped instance the
     * Compose shell resolves via `viewModel()` (one ViewModelStore, one
     * factory), so unlock/lock transitions observed here are exactly the
     * ones the vault screens drive.
     */
    private fun observeVaultWindowSecurity() {
        val vaultViewModel = ViewModelProvider(this).get(VaultViewModel::class.java)
        var collectJob: Job? = null
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        collectJob?.cancel()
                        collectJob = vaultWindowScope.launch {
                            vaultViewModel.state.collect { applyVaultWindowSecurity(it) }
                        }
                    }
                    // Hold the flag through ON_STOP (auto-lock + the recents
                    // snapshot); the next resume clears it if the session
                    // is no longer unlocked.
                    Lifecycle.Event.ON_PAUSE -> collectJob?.cancel()
                    Lifecycle.Event.ON_DESTROY -> vaultWindowScope.cancel()
                    else -> Unit
                }
            },
        )
    }

    /**
     * The D-2 flag rule itself: `FLAG_SECURE` exactly while the vault
     * session is unlocked, never otherwise.
     */
    private fun applyVaultWindowSecurity(state: VaultUiState) {
        if (state is VaultUiState.Unlocked) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
