package io.github.dukeoftoad.gallery

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.dukeoftoad.gallery.ui.ToadGalleryApp

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
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToadGalleryApp()
        }
    }
}
