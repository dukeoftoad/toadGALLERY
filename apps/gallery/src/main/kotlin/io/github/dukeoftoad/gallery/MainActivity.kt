package io.github.dukeoftoad.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.dukeoftoad.gallery.ui.ToadGalleryApp

/**
 * toadGALLERY product shell (roadmap Phase 2, ADR-0009).
 *
 * Compose-native single-activity shell: `setContent` hosts the Material 3 app with
 * a `NavHost` for the four top-level destinations (Photos, Albums, Search, Settings).
 * Replaces the superseded ADR-0005 View-based + `org.fossify:commons` shell
 * (`BaseSimpleActivity` + Fragments + XML layouts).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToadGalleryApp()
        }
    }
}
