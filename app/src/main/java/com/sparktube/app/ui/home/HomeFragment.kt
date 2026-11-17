package com.sparktube.app.ui.home

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Gravity
import android.widget.LinearLayout
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sparktube.app.R
import com.sparktube.app.data.Countries
import com.sparktube.app.data.YtRepository
import com.sparktube.app.data.VideoEntry
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.util.DeviceTuning
import com.sparktube.app.databinding.FragmentHomeBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.ScrollsToTop
import com.sparktube.app.ui.common.SkeletonAdapter
import com.sparktube.app.ui.common.showSkeleton
import com.sparktube.app.ui.common.toEntry
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.ui.search.SearchActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), ScrollsToTop {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private var loadedCountry: String? = null
    private var selectedCategoryQuery: String = "hindi trending songs"
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
            },
            onMenuClick = { model -> CardMenu.show(requireContext(), model) }
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setColorSchemeColors(com.sparktube.app.util.Themes.accentColor(requireContext()))
        binding.swipe.setOnRefreshListener { load() }
        com.sparktube.app.ui.music.Motion.pressable(binding.searchButton, 0.98f)

        binding.searchButton.setOnClickListener {
            startActivity(
                SearchActivity.intent(requireContext(), music = false)
            )
        }

        // v1.9.4: once the user has a usage profile, home defaults to the
        // personalized For You feed instead of a fixed category search.
        if (RecommendEngine.snapshot(requireContext()).hasSignal) {
            selectedCategoryQuery = FOR_YOU
        }
        setupCategoryChips()
        setupPersonalizedChips()

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
        // v1.7.11: the slow "All" (personalized home feed) category is removed;
        // every category is now a fast search query, with Hindi Trending Songs
        // as the home default.
        val chips: List<Pair<TextView, String>> = listOf(
            binding.chipHindiTrending to "hindi trending songs",
            binding.chipBanglaSong to "new bangla song latest",
            binding.chipBanglaNatok to "new bangla natok full",
            binding.chipLatestHindi to "latest hindi song new",
            binding.chipLofi to "lofi songs",
            binding.chipItemSong to "bangla hindi item song",
            binding.chipBanglaNews to "bangla news today latest",
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
            com.sparktube.app.ui.music.Motion.pressable(tv, 0.94f)
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

    private fun setupPersonalizedChips() {
        val row = binding.personalizedRow
        row.removeAllViews()
        val searches = LocalStore.searches(requireContext()).take(4)
        val history = LocalStore.history(requireContext()).take(3)
        val snap = RecommendEngine.snapshot(requireContext())
        val topKeywords = snap.keywordWeights.entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }
        val labels = LinkedHashSet<String>()
        labels.add("For You")
        topKeywords.forEach { if (it.length > 2) labels.add(it) }
        searches.forEach { if (it.length > 2) labels.add(it) }
        history.forEach {
            val words = it.title.trim().split(Regex("\\s+")).take(4).joinToString(" ")
            if (words.length > 2) labels.add(words)
        }
        if (labels.size == 1) {
            labels.add("Fresh Picks")
            labels.add("Trending Now")
        }
        labels.take(5).forEachIndexed { index, label ->
            val chip = TextView(requireContext()).apply {
                text = if (index == 0 && label == "For You") "✦ For You" else label
                setTextColor(requireContext().getColor(R.color.chip_text))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = requireContext().getDrawable(R.drawable.bg_chip_selected)
                isClickable = true
                isFocusable = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            com.sparktube.app.ui.music.Motion.pressable(chip, 0.95f)
            chip.setOnClickListener {
                val q = when (label) {
                    "For You" -> FOR_YOU
                    "Fresh Picks", "Trending Now" -> selectedCategoryQuery
                    else -> label
                }
                selectedCategoryQuery = q
                load()
            }
            row.addView(chip, LinearLayout.LayoutParams(dp(0), dp(34)).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                marginEnd = dp(8)
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun scrollToTop() {
        _binding?.list?.smoothScrollToPosition(0)
    }

    override fun onResume() {
        super.onResume()
        binding.countryFlag.text = Countries.flagOf(AppPrefs.countryOrDefault)
        setupPersonalizedChips()
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
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            b.errorView.isVisible = false
            b.emptyView.isVisible = false
            // v1.9.6: stale-while-revalidate ALWAYS — previously loaded cards appear
            // instantly even when the TTL expired, while the fresh feed loads below.
            var warm = LocalStore.readFeedCache(requireContext(), false, country, catQuery, DeviceTuning.feedCacheTtlMs(requireContext()))
            if (warm.isEmpty()) {
                warm = LocalStore.readFeedCache(requireContext(), false, country, catQuery, 0L)
            }
            if (warm.isNotEmpty()) {
                b.list.adapter = adapter
                adapter.submitList(warm.map { it.toUiModel() })
                b.list.alpha = 1f
            } else {
                showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 8)
            }
            try {
                val items = if (catQuery == FOR_YOU) {
                    YtRepository.personalizedFeed(country, RecommendEngine.snapshot(requireContext()))
                } else {
                    YtRepository.searchVideos(catQuery, null).items
                }
                LocalStore.saveFeedCache(requireContext(), false, country, catQuery, items.map { it.toUiModel().toEntry() })
                com.sparktube.app.util.DiagnosticsLogger.log("I", "Home", "category='$catQuery' items=${items.size}")
                b.list.alpha = 0f
                b.list.adapter = adapter
                adapter.submitList(items.map { it.toUiModel() })
                b.list.animate().alpha(1f).setDuration(220).start()
                b.emptyView.isVisible = items.isEmpty()
                if (items.isEmpty()) {
                    b.errorText.text = getString(R.string.error_no_results)
                    b.errorView.isVisible = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.sparktube.app.util.DiagnosticsLogger.log(
                    "E", "Home", "load FAIL cat='$catQuery' ${e.javaClass.simpleName}: ${e.message?.take(150) ?: ""}"
                )
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

        /** Sentinel category that loads the usage-based personalized feed. */
        const val FOR_YOU = "\u2726 For You"
    }
}
