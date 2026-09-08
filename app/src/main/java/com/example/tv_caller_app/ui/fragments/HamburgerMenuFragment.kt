package com.example.tv_caller_app.ui.fragments

import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.ViewCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.activities.MainActivity

class HamburgerMenuFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_hamburger_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_menu))

        view.findViewById<View>(R.id.btn_close_menu).setOnClickListener {
            dismiss()
        }

        view.findViewById<View>(R.id.menu_item_profile).setOnClickListener {
            dismiss()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, ProfileFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<View>(R.id.menu_item_settings).setOnClickListener {
            dismiss()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        val menuItemAi = view.findViewById<View>(R.id.menu_item_ai)
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val isTabletLandscape = resources.getBoolean(R.bool.is_large_screen) &&
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isTv || !isTabletLandscape) {
            menuItemAi.setOnClickListener {
                dismiss()
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.main_browse_fragment, AIFragment())
                    .addToBackStack(null)
                    .commit()
            }
        } else {
            menuItemAi.visibility = View.GONE
        }
    }
}
