package io.github.dukeoftoad.gallery

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.fossify.commons.extensions.applyColorFilter

/**
 * Placeholder screen carrying the Phase 2 screen-state system (roadmap: empty /
 * loading / permission-denied / unsupported / error). Each destination renders its
 * empty-state copy today; the permission state becomes live in Phase 3 when media
 * access lands (no permissions are requested by the shell — see AndroidManifest).
 */
class PlaceholderFragment : Fragment(R.layout.fragment_state) {

    private var destination = MainActivity.Destination.PHOTOS

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        destination = requireArguments().getSerializable(ARG_DESTINATION) as? MainActivity.Destination
            ?: MainActivity.Destination.PHOTOS

        val icon = view.findViewById<ImageView>(R.id.state_icon)
        val title = view.findViewById<TextView>(R.id.state_title)
        val body = view.findViewById<TextView>(R.id.state_body)
        val action = view.findViewById<Button>(R.id.state_action)

        val (iconRes, titleRes, bodyRes, actionVisible) = when (destination) {
            MainActivity.Destination.PHOTOS -> listOf(
                R.drawable.ic_nav_photos, R.string.state_photos_empty_title,
                R.string.state_photos_empty_body, false
            )
            MainActivity.Destination.ALBUMS -> listOf(
                R.drawable.ic_nav_albums, R.string.state_albums_empty_title,
                R.string.state_albums_empty_body, false
            )
            MainActivity.Destination.SEARCH -> listOf(
                R.drawable.ic_nav_search, R.string.state_search_empty_title,
                R.string.state_search_empty_body, false
            )
            MainActivity.Destination.SETTINGS -> listOf(
                R.drawable.ic_nav_settings, R.string.state_settings_empty_title,
                R.string.state_settings_empty_body, false
            )
        }

        icon.setImageResource(iconRes as Int)
        title.setText(titleRes as Int)
        body.setText(bodyRes as Int)
        action.visibility = if (actionVisible as Boolean) View.VISIBLE else View.GONE

        // Neutral tint consistent with the state system until the design system freezes tokens (issue #11)
        icon.applyColorFilter(requireContext().getColor(R.color.state_icon_tint))
    }

    companion object {
        private const val ARG_DESTINATION = "destination"

        fun newInstance(destination: MainActivity.Destination): PlaceholderFragment {
            return PlaceholderFragment().apply {
                arguments = Bundle().apply { putSerializable(ARG_DESTINATION, destination) }
            }
        }
    }
}
