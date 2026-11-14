package com.sparktube.app.ui.home

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sparktube.app.BuildConfig
import com.sparktube.app.R
import com.sparktube.app.data.Countries
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.FragmentHomeBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.ScrollsToTop
import com.sparktube.app.ui.common.SkeletonAdapter
import com.sparktube.app.ui.common.showSkeleton
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.menu.SettingsPageActivity
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.ui.search.SearchActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.CrashReporter
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment(), ScrollsToTop {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private var loadedCountry: String? = null
    private var selectedCategoryQuery: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var lastLoadedAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = VideoAdapter(
            onClick = { model ->
                PlayerActivity.start(requireContext(), model)
            },
            onAudioClick = { model ->
                val entry = model.toQueueEntry(isMusic = true)
                PlaybackCenter.playMusic(entry, radio = true)
                Toast.makeText(
                    requireContext(),
                    R.string.switched_to_audio_player,
                    Toast.LENGTH_SHORT
                ).show()
                NowPlayingActivity.start(requireContext())
            }
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { load() }

        binding.searchButton.setOnClickListener {
            startActivity(
                SearchActivity.intent(requireContext(), music = false)
            )
        }

        binding.headerProfileChip.setOnClickListener {
            SettingsPageActivity.start(requireContext(), SettingsPageActivity.TYPE_GENERAL)
        }

        setupCategoryChips()

        binding.crashTestButton.isVisible = BuildConfig.DEBUG
        binding.crashTestButton.setOnClickListener {
            CrashReporter.logBreadcrumb("Test crash button tapped on Home")
            CrashReporter.logBreadcrumb(
                "loaded_country=${loadedCountry ?: "<not loaded>"}"
            )
            throw RuntimeException(
                "SparkTube test crash — local crash report check"
            )
        }

        binding.retryButton.setOnClickListener { load() }

        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_MS)
                if (_binding != null && isAdded && !isHidden && stale()) {
                    load()
                }
            }
        }
    }

    private fun setupCategoryChips() {
        val chips: List<Pair<TextView, String?>> = listOf(
            binding.chipAll to null,
            binding.chipMusic to "music",
            binding.chipGaming to "gaming",
            binding.chipTech to "technology ai review",
            binding.chipPodcasts to "podcast episodes",
            binding.chipMovies to "movie trailers"
        )
        fun updateChipSelection(activeQuery: String?) {
            chips.forEach { (tv, q) ->
                tv.isSelected = (q == activeQuery)
            }
        }
        updateChipSelection(selectedCategoryQuery)

        chips.forEach { (tv, query) ->
            tv.setOnClickListener {
                if (selectedCategoryQuery == query && !stale()) {
                    scrollToTop()
                    return@setOnClickListener
                }
                selectedCategoryQuery = query
                updateChipSelection(query)
                load()
            }
        }
    }

    override fun scrollToTop() {
        _binding?.list?.smoothScrollToPosition(0)
    }

    override fun onResume() {
        super.onResume()
        binding.countryFlag.text = Countries.flagOf(AppPrefs.countryOrDefault)
        maybeReload()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            binding.countryFlag.text = Countries.flagOf(AppPrefs.countryOrDefault)
            maybeReload()
        }
    }

    private fun stale(): Boolean =
        lastLoadedAt == 0L || SystemClock.elapsedRealtime() - lastLoadedAt >= AUTO_REFRESH_MS

    private fun maybeReload() {
        val current = AppPrefs.countryOrDefault
        if (current != loadedCountry || stale()) {
            load()
        }
    }

    private fun load() {
        val country = AppPrefs.countryOrDefault
        val catQuery = selectedCategoryQuery
        loadedCountry = country
        lastLoadedAt = SystemClock.elapsedRealtime()

        loadJob?.cancel()
        val b = _binding ?: return
        val ctx = requireContext()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            b.errorView.isVisible = false
            b.emptyView.isVisible = false
            showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 8)
            try {
                val items = if (catQuery.isNullOrBlank()) {
                    val snap = withContext(Dispatchers.IO) {
                        RecommendEngine.snapshot(ctx)
                    }
                    YtRepository.personalizedFeed(country, snap)
                } else {
                    YtRepository.searchVideos(catQuery, null).items
                }
                b.list.adapter = adapter
                adapter.submitList(items.map { it.toUiModel() })
                b.emptyView.isVisible = items.isEmpty()
                if (items.isEmpty()) {
                    b.errorText.text = getString(R.string.error_no_results)
                    b.errorView.isVisible = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                b.list.adapter = adapter
                adapter.submitList(emptyList())
                b.errorText.text = Formatters.friendlyException(e)
                b.errorView.isVisible = true
                lastLoadedAt = 0L
            } finally {
                b.swipe.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val AUTO_REFRESH_MS = 5 * 60 * 1000L
    }
}
