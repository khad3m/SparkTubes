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
        Motion.pressable(binding.rowCountry, 0.96f)
        Motion.pressable(binding.rowSettings, 0.96f)
        Motion.pressable(binding.rowVideoSettings, 0.96f)
        Motion.pressable(binding.rowMusicSettings, 0.96f)
        Motion.pressable(binding.rowGoogleAccount, 0.96f)
        Motion.pressable(binding.rowCheckUpdate, 0.96f)

        binding.profileHeroCard.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_GENERAL)
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
        binding.rowGoogleAccount.setOnClickListener {
            com.sparktube.app.ui.auth.YouTubeLoginActivity.start(requireContext())
        }
        binding.rowCheckUpdate.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_GENERAL)
        }
    }

    override fun onResume() {
        super.onResume()
        val country = AppPrefs.countryOrDefault
        binding.countryValue.text = "${Countries.flagOf(country)} ${Countries.nameOf(country)}"
        // Google account management lives in Settings → "YouTube / Google Account";
        // the menu keeps only the profile card (v1.7.0 removed the duplicate card here).
        val loggedIn = YouTubeAuth.isLoggedIn()
        binding.profileSubtitle.text = if (loggedIn) {
            getString(R.string.youtube_account_connected_sub)
        } else {
            getString(R.string.profile_subtitle)
        }
        binding.accountValue.text = YouTubeAuth.accountSummary()
        binding.versionValue.text = "v${com.sparktube.app.BuildConfig.VERSION_NAME}" 
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
