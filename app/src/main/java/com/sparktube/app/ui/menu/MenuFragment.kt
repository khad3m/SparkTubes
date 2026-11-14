package com.sparktube.app.ui.menu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sparktube.app.R
import com.sparktube.app.data.Countries
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.FragmentMenuBinding
import com.sparktube.app.net.YouTubeAuth
import com.sparktube.app.ui.auth.YouTubeLoginActivity
import com.sparktube.app.ui.music.Motion
import com.sparktube.app.util.AppPrefs
import kotlinx.coroutines.launch

class MenuFragment : Fragment() {

    private var _binding: FragmentMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Motion.pressable(binding.profileHeroCard, 0.98f)
        Motion.pressable(binding.rowGoogleAccount, 0.97f)
        Motion.pressable(binding.rowCountry, 0.96f)
        Motion.pressable(binding.rowSettings, 0.96f)
        Motion.pressable(binding.rowVideoSettings, 0.96f)
        Motion.pressable(binding.rowMusicSettings, 0.96f)

        binding.profileHeroCard.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_GENERAL)
        }
        binding.rowGoogleAccount.setOnClickListener {
            handleGoogleAccountClick()
        }
        binding.rowCountry.setOnClickListener { showCountryPicker() }
        binding.rowSettings.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_GENERAL)
        }
        binding.rowVideoSettings.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_VIDEO)
        }
        binding.rowMusicSettings.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_MUSIC)
        }
    }

    override fun onResume() {
        super.onResume()
        val country = AppPrefs.countryOrDefault
        binding.countryValue.text = "${Countries.flagOf(country)} ${Countries.nameOf(country)}"
        bindGoogleAccountState()
    }

    private fun bindGoogleAccountState() {
        val loggedIn = YouTubeAuth.isLoggedIn()
        if (loggedIn) {
            binding.googleAccountTitle.text = getString(R.string.youtube_account_connected)
            binding.googleAccountSubtitle.text = getString(R.string.youtube_account_connected_sub)
            binding.googleAccountBadge.text = getString(R.string.youtube_account_active_badge)
            binding.googleAccountBadge.setBackgroundResource(R.drawable.bg_pill_green)
            binding.profileSubtitle.text = "Premium Glass Edition · Google Auth + VR Engine Active"
        } else {
            binding.googleAccountTitle.text = getString(R.string.youtube_account_title)
            binding.googleAccountSubtitle.text = getString(R.string.youtube_account_disconnected_sub)
            binding.googleAccountBadge.text = getString(R.string.youtube_sign_in_short)
            binding.googleAccountBadge.setBackgroundResource(R.drawable.bg_action_pill_active)
            binding.profileSubtitle.text = getString(R.string.profile_subtitle)
        }
    }

    private fun handleGoogleAccountClick() {
        if (!YouTubeAuth.isLoggedIn()) {
            YouTubeLoginActivity.start(requireContext())
            return
        }
        val options = arrayOf(
            getString(R.string.youtube_account_relogin),
            getString(R.string.youtube_account_sign_out)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.youtube_account_connected)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> YouTubeLoginActivity.start(requireContext())
                    1 -> {
                        YouTubeAuth.clearSession()
                        YtRepository.clearCache()
                        bindGoogleAccountState()
                        Toast.makeText(
                            requireContext(),
                            R.string.youtube_signed_out_toast,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCountryPicker() {
        val names = Countries.all.map { "${Countries.flagOf(it.code)} ${it.name}" }.toTypedArray()
        val current = Countries.all.indexOfFirst { it.code == AppPrefs.countryOrDefault }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.change_country)
            .setSingleChoiceItems(names, if (current >= 0) current else 0) { dialog, which ->
                val country = Countries.all[which]
                AppPrefs.country = country.code
                requireActivity().lifecycleScope.launch {
                    YtRepository.applyCountry(country.code)
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.country_changed, Countries.nameOf(country.code)),
                    Toast.LENGTH_SHORT
                ).show()
                binding.countryValue.text =
                    "${Countries.flagOf(country.code)} ${Countries.nameOf(country.code)}"
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
