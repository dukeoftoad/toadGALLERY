package io.github.dukeoftoad.gallery

import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.fossify.commons.activities.BaseSimpleActivity

/**
 * toadGALLERY product shell (roadmap Phase 2).
 *
 * Four top-level destinations (ADR-0005): Photos, Albums, Search, Settings.
 * The shell deliberately contains NO browsing logic: Phase 3 media access plugs
 * into the placeholder state system instead of being coupled to this skeleton.
 */
class MainActivity : BaseSimpleActivity() {

    private lateinit var bottomNav: BottomNavigationView

    // commons base contract
    override fun getAppIconIDs() = arrayListOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)
    override fun getAppLauncherName() = getString(R.string.app_launcher_name)
    override fun getRepositoryName() = "toadGALLERY"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bottomNav = findViewById(R.id.bottom_nav)

        if (savedInstanceState == null) {
            showFragment(Destination.PHOTOS)
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_photos -> showFragment(Destination.PHOTOS)
                R.id.nav_albums -> showFragment(Destination.ALBUMS)
                R.id.nav_search -> showFragment(Destination.SEARCH)
                R.id.nav_settings -> showFragment(Destination.SETTINGS)
            }
            true
        }
    }

    private fun showFragment(destination: Destination) {
        val tag = destination.name
        var fragment = supportFragmentManager.findFragmentByTag(tag)
        if (fragment == null) {
            fragment = PlaceholderFragment.newInstance(destination)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commitNow()
    }

    enum class Destination { PHOTOS, ALBUMS, SEARCH, SETTINGS }
}
