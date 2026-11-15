package com.sparktube.app.ui.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sparktube.app.R
import com.sparktube.app.data.ChannelEntry
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.RecommendEngine
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivitySearchBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.playback.toQueueEntry
import com.sparktube.app.ui.channel.ChannelActivity
import com.sparktube.app.ui.common.ChannelRowAdapter
import com.sparktube.app.ui.common.MusicRowAdapter
import com.sparktube.app.ui.common.SkeletonAdapter
import com.sparktube.app.ui.common.SuggestionAdapter
import com.sparktube.app.ui.common.SuggestionRow
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.common.VideoUiModel
import com.sparktube.app.ui.common.showSkeleton
import com.sparktube.app.ui.common.toQueueEntry
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Themes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/**
 * Search screen for both videos and music. While the user types, keyword
 * suggestions from YouTube are shown; picking one runs the search.
 *
 * Video search has two result tabs — Videos and Channels — plus
 * client-side upload-date filters (today / this week / this month) applied
 * to the parsed "N days ago" labels of each result. The filters live behind
 * a dedicated funnel button in the search bar (tinted to the accent colour
 * while a filter is active) instead of a permanent row of chips. While
 * results load, skeleton rows stand in for the list instead of a spinner.
 */
class SearchActivity : AppCompatActivity() {

    /** Result tab shown on the video search screen. */
    private enum class SearchTab { VIDEOS, CHANNELS, PLAYLISTS, SHORTS }

    private lateinit var binding: ActivitySearchBinding
    private var musicMode: Boolean = false
    private var prefillQuery: String? = null
    private lateinit var miniBar: com.sparktube.app.ui.common.MiniPlayerBar

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var channelAdapter: ChannelRowAdapter
    private lateinit var musicAdapter: MusicRowAdapter
    private lateinit var playlistAdapter: com.sparktube.app.ui.common.RemotePlaylistAdapter
    private lateinit var suggestionAdapter: SuggestionAdapter

    private val items = mutableListOf<VideoUiModel>()
    private val channelItems = mutableListOf<ChannelEntry>()
    private val playlistItems = mutableListOf<com.sparktube.app.ui.common.RemotePlaylistModel>()
    private var page: Page? = null
    private var query: String = ""
    private var isLoading = false
    private var searchInFlight = false
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var lastSuggestions: List<String> = emptyList()

    private var tab: SearchTab = SearchTab.VIDEOS

    /** Active upload-date filter for the Videos tab (null = any time). */
    private var dateWindow: Formatters.DateWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicMode = intent.getBooleanExtra(EXTRA_MUSIC, false)
        prefillQuery = intent.getStringExtra(EXTRA_PREFILL)

        if (musicMode) {
            binding.searchInput.hint = getString(R.string.search_music_hint)
        } else {
            // Tabs + the filter button are a video-search feature only.
            binding.tabRow.isVisible = true
            binding.tabVideos.isSelected = true
            binding.filterButton.isVisible = true

            binding.tabVideos.setOnClickListener { switchTab(SearchTab.VIDEOS) }
            binding.tabChannels.setOnClickListener { switchTab(SearchTab.CHANNELS) }
            binding.tabPlaylists.setOnClickListener { switchTab(SearchTab.PLAYLISTS) }
            binding.tabShorts.setOnClickListener { switchTab(SearchTab.SHORTS) }
            // Shorts / Reels search tab follows the Settings toggle (v1.7.0).
            binding.tabShorts.isVisible = AppPrefs.shortsEnabled
            binding.filterButton.setOnClickListener { showFilterPicker() }
        }

        videoAdapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(this, model)
        })
        channelAdapter = ChannelRowAdapter(
            onClick = { channel ->
                ChannelActivity.start(this, channel.url, channel.name)
            }
        )
        musicAdapter = MusicRowAdapter(onClick = { model ->
            playMusic(model)
        })
        playlistAdapter = com.sparktube.app.ui.common.RemotePlaylistAdapter(onClick = { playlist ->
            openRemotePlaylist(playlist)
        })
        suggestionAdapter = SuggestionAdapter(
            onClick = { suggestion ->
                binding.searchInput.setText(suggestion)
                binding.searchInput.setSelection(suggestion.length)
                doSearch()
            },
            onDelete = { entry ->
                LocalStore.removeSearch(this, entry, musicMode)
                val text = binding.searchInput.text?.toString().orEmpty()
                if (text.length < 2) {
                    showHistoryRows()
                } else {
                    showMergedRows(text, lastSuggestions)
                }
            }
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = if (musicMode) musicAdapter else videoAdapter
        binding.suggestionList.layoutManager = LinearLayoutManager(this)
        binding.suggestionList.adapter = suggestionAdapter

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= itemCount() - 4 && page != null && !isLoading) {
                    loadMore()
                }
            }
        })

        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener {
            binding.searchInput.setText("")
            showHistoryRows()
            binding.searchInput.requestFocus()
        }

        // Floating mini player above the results (v1.7.0): appears whenever
        // something is playing, same as the bar above the home-screen navbar.
        miniBar = com.sparktube.app.ui.common.MiniPlayerBar(binding.miniBarInclude.root) {
            com.sparktube.app.ui.player.PlayerActivity.startResume(this)
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                binding.clearButton.isVisible = text.isNotEmpty()
                if (text == query) {
                    // The input matches what is already searched: show results.
                    return
                }
                if (text.length < 2) {
                    suggestionJob?.cancel()
                    lastSuggestions = emptyList()
                    showHistoryRows()
                    return
                }
                queueSuggestions(text)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.searchInput.requestFocus()
        showKeyboard()
        // Fresh open with an empty input: surface the recent searches.
        if (prefillQuery.isNullOrBlank()) {
            showHistoryRows()
        }

        prefillQuery?.takeIf { it.isNotBlank() }?.let { prefill ->
            binding.searchInput.setText(prefill)
            binding.searchInput.setSelection(prefill.length)
            doSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were in the background.
        Themes.recreateIfNeeded(this)
        miniBar.start()
    }

    override fun onPause() {
        super.onPause()
        miniBar.stop()
    }

    // ----- Tabs + date filter -----

    private fun switchTab(newTab: SearchTab) {
        if (newTab == tab) return
        tab = newTab
        binding.tabVideos.isSelected = tab == SearchTab.VIDEOS
        binding.tabChannels.isSelected = tab == SearchTab.CHANNELS
        binding.tabPlaylists.isSelected = tab == SearchTab.PLAYLISTS
        binding.tabShorts.isSelected = tab == SearchTab.SHORTS
        // Upload-date filters only make sense for videos / shorts.
        binding.filterButton.isVisible = tab == SearchTab.VIDEOS || tab == SearchTab.SHORTS
        // Re-run the search for the new tab when there is a query; a
        // half-finished old-tab search is cancelled by doSearch's new job.
        if (query.isNotBlank()) {
            searchJob?.cancel()
            doSearch()
        } else {
            applyListAdapter()
        }
    }

    /**
     * The dedicated Filters button: one dialog with the four upload-date
     * windows — no permanent chip row cluttering the screen.
     */
    private fun showFilterPicker() {
        val options = listOf(
            null to getString(R.string.filter_any),
            Formatters.DateWindow.TODAY to getString(R.string.filter_today),
            Formatters.DateWindow.THIS_WEEK to getString(R.string.filter_this_week),
            Formatters.DateWindow.THIS_MONTH to getString(R.string.filter_this_month)
        )
        val current = options.indexOfFirst { it.first == dateWindow }
        AlertDialog.Builder(this)
            .setTitle(R.string.search_filters)
            .setSingleChoiceItems(
                options.map { it.second }.toTypedArray(),
                if (current >= 0) current else 0
            ) { dialog, which ->
                dialog.dismiss()
                pickDateFilter(options[which].first)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDateFilter(window: Formatters.DateWindow?) {
        if (dateWindow == window) return
        dateWindow = window
        // Accent tint while a filter is active, muted otherwise.
        binding.filterButton.setColorFilter(
            if (window == null) getColor(R.color.on_surface_variant)
            else Themes.accentColor(this)
        )
        if (query.isNotBlank()) {
            searchJob?.cancel()
            doSearch()
        }
    }

    /** Points the results list at the adapter of the active tab. */
    private fun applyListAdapter() {
        binding.list.adapter = when {
            musicMode -> musicAdapter
            tab == SearchTab.CHANNELS -> channelAdapter
            tab == SearchTab.PLAYLISTS -> playlistAdapter
            else -> videoAdapter
        }
    }

    // ----- Search -----

    private fun playMusic(model: VideoUiModel) {
        PlaybackCenter.playMusic(model.toQueueEntry(isMusic = true), radio = true)
        NowPlayingActivity.start(this)
    }

    private fun itemCount(): Int = when {
        musicMode -> musicAdapter.itemCount
        tab == SearchTab.CHANNELS -> channelAdapter.itemCount
        tab == SearchTab.PLAYLISTS -> playlistAdapter.itemCount
        else -> videoAdapter.itemCount
    }

    /**
     * Opens a YouTube playlist found in Search: fetches its videos, then lets
     * the user play them as a video queue (watch page) or as an audio/music
     * queue (Now Playing with radio auto-continuation).
     */
    private fun openRemotePlaylist(playlist: com.sparktube.app.ui.common.RemotePlaylistModel) {
        Toast.makeText(this, R.string.playlist_loading_toast, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val videos = YtRepository.playlistItems(playlist.url)
                if (videos.isEmpty()) {
                    Toast.makeText(this@SearchActivity, R.string.playlist_load_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val entries = videos.map { it.toQueueEntry(isMusic = false) }
                val choices = arrayOf(
                    getString(R.string.playlist_play_video),
                    getString(R.string.playlist_play_audio)
                )
                AlertDialog.Builder(this@SearchActivity)
                    .setTitle(playlist.title)
                    .setItems(choices) { dialog, which ->
                        dialog.dismiss()
                        when (which) {
                            0 -> {
                                PlaybackCenter.playPlaylist(entries, 0)
                                PlayerActivity.startResume(this@SearchActivity)
                            }
                            else -> {
                                val music = entries.map { it.copy(isMusic = true) }
                                PlaybackCenter.playMusicQueue(music, 0, allowRadio = true)
                                NowPlayingActivity.start(this@SearchActivity)
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@SearchActivity, R.string.playlist_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queueSuggestions(text: String) {
        suggestionJob?.cancel()
        suggestionJob = lifecycleScope.launch {
            delay(250)
            try {
                val suggestions = YtRepository.suggestions(text)
                if (binding.searchInput.text?.toString() == text) {
                    lastSuggestions = suggestions
                    showMergedRows(text, suggestions)
                }
            } catch (e: Exception) {
                // Suggestions are best-effort only.
            }
        }
    }

    /** Empty input: show the saved search history (newest first). */
    private fun showHistoryRows() {
        val searches = LocalStore.searches(this, musicMode).take(15)
        if (searches.isEmpty()) {
            suggestionAdapter.submitList(emptyList())
            binding.suggestionList.isVisible = false
            return
        }
        suggestionAdapter.submitList(searches.map { SuggestionRow(it, isHistory = true) })
        binding.suggestionList.isVisible = true
    }

    /** Typing: matching saved searches on top, remote suggestions below. */
    private fun showMergedRows(text: String, suggestions: List<String>) {
        val lower = text.lowercase()
        val historyMatches = LocalStore.searches(this, musicMode)
            .filter { it.lowercase().contains(lower) }
            .take(5)
        val historyKeys = historyMatches.map { it.lowercase() }.toSet()
        val rows = historyMatches.map { SuggestionRow(it, isHistory = true) } +
            suggestions
                .filter { it.lowercase() !in historyKeys }
                .take(10)
                .map { SuggestionRow(it, isHistory = false) }

        val showResults = itemCount() > 0 || searchInFlight || binding.errorView.isVisible
        if (rows.isEmpty() || (showResults && rows.firstOrNull()?.text == query)) {
            binding.suggestionList.isVisible = false
        } else {
            binding.suggestionList.isVisible = true
        }
        suggestionAdapter.submitList(rows)
    }

    private fun doSearch() {
        val text = binding.searchInput.text?.toString()?.trim().orEmpty()
        hideKeyboard()
        if (text.isEmpty()) return
        query = text
        page = null
        items.clear()
        channelItems.clear()
        playlistItems.clear()
        RecommendEngine.logSearch(this, text, musicMode)
        binding.suggestionList.isVisible = false
        videoAdapter.submitList(emptyList())
        channelAdapter.submitList(emptyList())
        musicAdapter.submitList(emptyList())
        playlistAdapter.submitList(emptyList())
        binding.errorView.isVisible = false
        binding.emptyView.isVisible = false
        searchInFlight = true

        // Skeleton rows stand in for the incoming results (video cards for
        // the Videos tab, compact rows for channels / music / playlists).
        val style = when {
            !musicMode && tab == SearchTab.VIDEOS -> SkeletonAdapter.STYLE_VIDEO
            !musicMode && tab == SearchTab.SHORTS -> SkeletonAdapter.STYLE_VIDEO
            else -> SkeletonAdapter.STYLE_ROW
        }
        showSkeleton(binding.list, style, count = 8)

        searchJob = lifecycleScope.launch {
            val self = coroutineContext[Job]
            try {
                when {
                    musicMode -> {
                        val result = YtRepository.searchMusic(query, null)
                        items.addAll(result.items.map { it.toUiModel() })
                        page = result.nextPage
                        applyListAdapter()
                        musicAdapter.submitList(items.toList())
                        finishSearch(items.isNotEmpty(), musicEmpty)
                    }
                    tab == SearchTab.CHANNELS -> {
                        val result = YtRepository.searchChannels(query, null)
                        channelItems.addAll(result.items)
                        page = result.nextPage
                        applyListAdapter()
                        channelAdapter.submitList(channelItems.toList())
                        finishSearch(channelItems.isNotEmpty(), channelsEmpty)
                    }
                    tab == SearchTab.PLAYLISTS -> {
                        val result = YtRepository.searchPlaylists(query, null)
                        playlistItems.addAll(
                            result.items.map {
                                com.sparktube.app.ui.common.RemotePlaylistModel(
                                    url = it.url,
                                    title = it.title,
                                    uploader = it.uploader,
                                    thumbnailUrl = it.thumbnailUrl,
                                    videoCount = it.videoCount
                                )
                            }
                        )
                        page = result.nextPage
                        applyListAdapter()
                        playlistAdapter.submitList(playlistItems.toList())
                        finishSearch(playlistItems.isNotEmpty(), noResultsEmpty)
                    }
                    tab == SearchTab.SHORTS -> {
                        // Shorts search: short-form clips (≤ 3 minutes) from the
                        // video search, filtered client-side (v1.7.0).
                        val result = YtRepository.searchVideos(query, null)
                        items.clear()
                        items.addAll(
                            result.items.map { it.toUiModel() }
                                .filter { it.durationSec in 1L..180L }
                        )
                        page = result.nextPage
                        applyListAdapter()
                        videoAdapter.submitList(items.toList())
                        finishSearch(items.isNotEmpty(), noResultsEmpty)
                    }
                    else -> {
                        val result = YtRepository.searchVideos(query, null)
                        // Upload-date filter is applied client-side: NewPipe's
                        // YouTube search has no server-side date parameter,
                        // but every result carries a "N days ago" label we
                        // can parse.
                        val filtered = result.items.map { it.toUiModel() }
                            .filter { passesDateFilter(it) }
                        items.addAll(filtered)
                        page = result.nextPage
                        applyListAdapter()
                        videoAdapter.submitList(items.toList())
                        finishSearch(items.isNotEmpty(), noResultsEmpty)
                    }
                }
            } catch (e: CancellationException) {
                // Switching tab / filter cancels the in-flight search: not
                // an error, the replacement search is already running.
                throw e
            } catch (e: Exception) {
                // Drop the skeleton behind the error message.
                applyListAdapter()
                binding.errorText.text = Formatters.friendlyException(e)
                binding.errorView.isVisible = true
            } finally {
                if (searchJob === self) searchInFlight = false
            }
        }
    }

    private val musicEmpty: String get() = getString(R.string.error_no_songs)
    private val channelsEmpty: String get() = getString(R.string.error_no_channels)
    private val noResultsEmpty: String get() = getString(R.string.error_no_results)

    private fun finishSearch(anyResults: Boolean, emptyMessage: String) {
        if (!anyResults) {
            binding.emptyView.isVisible = true
            binding.errorText.text = emptyMessage
            binding.errorView.isVisible = true
        }
    }

    /** Client-side upload-date check for one video result. */
    private fun passesDateFilter(model: VideoUiModel): Boolean {
        val window = dateWindow ?: return true
        return Formatters.withinDateWindow(model.uploadDate, window)
    }

    private fun loadMore() {
        val nextPage = page ?: return
        isLoading = true
        lifecycleScope.launch {
            try {
                when {
                    musicMode -> {
                        val result = YtRepository.searchMusic(query, nextPage)
                        val known = items.map { it.url }.toSet()
                        items.addAll(result.items.map { it.toUiModel() }.filter { it.url !in known })
                        page = result.nextPage
                        musicAdapter.submitList(items.toList())
                    }
                    tab == SearchTab.CHANNELS -> {
                        val result = YtRepository.searchChannels(query, nextPage)
                        val known = channelItems.map { it.url }.toSet()
                        channelItems.addAll(result.items.filter { it.url !in known })
                        page = result.nextPage
                        channelAdapter.submitList(channelItems.toList())
                    }
                    tab == SearchTab.PLAYLISTS -> {
                        val result = YtRepository.searchPlaylists(query, nextPage)
                        val known = playlistItems.map { it.url }.toSet()
                        playlistItems.addAll(
                            result.items
                                .filter { it.url !in known }
                                .map {
                                    com.sparktube.app.ui.common.RemotePlaylistModel(
                                        url = it.url,
                                        title = it.title,
                                        uploader = it.uploader,
                                        thumbnailUrl = it.thumbnailUrl,
                                        videoCount = it.videoCount
                                    )
                                }
                        )
                        page = result.nextPage
                        playlistAdapter.submitList(playlistItems.toList())
                    }
                    tab == SearchTab.SHORTS -> {
                        val result = YtRepository.searchVideos(query, nextPage)
                        val known = items.map { it.url }.toSet()
                        items.addAll(
                            result.items.map { it.toUiModel() }
                                .filter { it.url !in known && it.durationSec in 1L..180L }
                        )
                        page = result.nextPage
                        videoAdapter.submitList(items.toList())
                    }
                    else -> {
                        val result = YtRepository.searchVideos(query, nextPage)
                        val known = items.map { it.url }.toSet()
                        items.addAll(
                            result.items.map { it.toUiModel() }
                                .filter { it.url !in known && passesDateFilter(it) }
                        )
                        page = result.nextPage
                        videoAdapter.submitList(items.toList())
                    }
                }
            } catch (e: Exception) {
                page = null
            } finally {
                isLoading = false
            }
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    companion object {
        private const val EXTRA_MUSIC = "extra_music"
        private const val EXTRA_PREFILL = "extra_prefill"

        fun intent(context: Context, music: Boolean, prefill: String? = null): Intent =
            Intent(context, SearchActivity::class.java).apply {
                putExtra(EXTRA_MUSIC, music)
                putExtra(EXTRA_PREFILL, prefill)
            }
    }
}
