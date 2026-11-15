package com.sparktube.app.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sparktube.app.BuildConfig
import com.sparktube.app.R
import com.sparktube.app.databinding.ActivityMainBinding
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.home.HomeFragment
import com.sparktube.app.ui.library.LibraryFragment
import com.sparktube.app.ui.menu.MenuFragment
import com.sparktube.app.ui.music.MusicFragment
import com.sparktube.app.ui.music.NowPlayingActivity
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import com.sparktube.app.util.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedId: Int = R.id.navHome
    private val fragments = mutableMapOf<Int, Fragment>()
    private var lastBackPress = 0L
    private var lastMiniThumbKey: String? = null

    private val progressHandler = Handler(Looper.getMainLooper())

    private val playbackListener = object : PlaybackCenter.Listener {
        override fun onItemChanged(entry: com.sparktube.app.playback.QueueEntry?) = bindMiniPlayer()
        override fun onPlaybackStateChanged(isPlaying: Boolean) = updateMiniPlayer()
        override fun onQueueChanged() = updateMiniPlayer()
        override fun onAudioOnlyChanged(audioOnly: Boolean) = bindMiniPlayer()
        override fun onFavoriteChanged(url: String, isFavorite: Boolean) = updateMiniPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            selectedId = savedInstanceState.getInt(STATE_SELECTED, R.id.navHome)
            NAV_IDS.forEach { id ->
                supportFragmentManager.findFragmentByTag(tagOf(id))?.let {
                    fragments[id] = it
                }
            }
        }

        requestNotificationPermissionIfNeeded()
        maybeSilentUpdateCheck()

        listOf(binding.navHome, binding.navMusic, binding.navLibrary, binding.navMenu)
            .forEach { com.sparktube.app.ui.music.Motion.pressable(it, 0.92f) }
        com.sparktube.app.ui.music.Motion.pressable(binding.navShorts, 0.92f)
        binding.navHome.setOnClickListener { select(R.id.navHome) }
        binding.navMusic.setOnClickListener { select(R.id.navMusic) }
        binding.navLibrary.setOnClickListener { select(R.id.navLibrary) }
        binding.navMenu.setOnClickListener { select(R.id.navMenu) }
        // Shorts / Reels shortcut — present only while the feature is enabled
        // in Settings → Shorts / Reels videos (v1.7.0).
        binding.navShorts.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            com.sparktube.app.ui.shorts.ShortsActivity.start(this)
        }
        binding.navShorts.isVisible = AppPrefs.shortsEnabled

        binding.miniMedia.clipToOutline = true
        binding.miniMedia.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 10f * resources.displayMetrics.density)
            }
        }

        binding.miniPlayPause.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            PlaybackCenter.togglePlayPause()
        }
        binding.miniClose.setOnClickListener {
            PlaybackCenter.stopPlayback()
        }
        binding.miniPlayer.setOnClickListener { openPlayerFromMini() }
        installMiniPlayerGestures()

        // Keep scrollable content clear of the floating nav + mini player, whatever their height.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyBottomClearance() }
        binding.floatingNav.addOnLayoutChangeListener(relayout)
        binding.miniPlayer.addOnLayoutChangeListener(relayout)
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    v.post { applyBottomClearance() }
                }
            },
            false
        )

        applySelection()
    }

    /**
     * Bottom space (px) taken by the floating nav and, when shown, the mini player above it.
     */
    private fun bottomClearancePx(): Int {
        val rootH = binding.root.height
        if (rootH <= 0) return 0
        val navTop = if (binding.floatingNav.height > 0) binding.floatingNav.top else rootH
        val top = if (binding.miniPlayer.isVisible && binding.miniPlayer.height > 0) {
            minOf(navTop, binding.miniPlayer.top)
        } else navTop
        return (rootH - top).coerceAtLeast(0)
    }

    private fun applyBottomClearance() {
        if (!::binding.isInitialized) return
        // Floor of 100dp: keeps the last menu/settings card visibly above the
        // floating nav even before the first layout pass lands.
        val floor = (100 * resources.displayMetrics.density).toInt()
        val clearance = maxOf(bottomClearancePx() + (12 * resources.displayMetrics.density).toInt(), floor)
        applyClearanceTo(binding.container, clearance)
    }

    private fun applyClearanceTo(view: View, clearance: Int) {
        val verticalList = view is RecyclerView &&
            (view.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.orientation == androidx.recyclerview.widget.RecyclerView.VERTICAL
        if (view is ScrollView || view is NestedScrollView || verticalList) {
            val g = view as ViewGroup
            g.clipToPadding = false
            if (g.paddingBottom != clearance) {
                g.setPadding(g.paddingLeft, g.paddingTop, g.paddingRight, clearance)
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyClearanceTo(view.getChildAt(i), clearance)
        }
    }

    /** Runs once per app session if "Auto check for updates" is enabled in Settings. */
    private fun maybeSilentUpdateCheck() {
        if (!AppPrefs.autoCheckUpdates || UpdateChecker.silentCheckDone) return
        UpdateChecker.silentCheckDone = true
        lifecycleScope.launch {
            val latest = UpdateChecker.fetchLatest() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            if (UpdateChecker.isNewer(BuildConfig.VERSION_NAME, latest.tag)) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("${getString(R.string.update_available_title)} (${latest.tag})")
                    .setMessage(latest.body.ifBlank { latest.name })
                    .setPositiveButton(R.string.update_download) { _, _ ->
                        // In-app download with a live progress bar, then the
                        // system installer — no browser involved.
                        com.sparktube.app.ui.common.UpdateDownloadUi.start(this@MainActivity, latest, lifecycleScope)
                    }
                    .setNegativeButton(R.string.later, null)
                    .show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED, selectedId)
    }

    override fun onResume() {
        super.onResume()
        if (Themes.recreateIfNeeded(this)) return
        // The Shorts toggle may have changed in Settings while we were away.
        binding.navShorts.isVisible = AppPrefs.shortsEnabled
        PlaybackCenter.addListener(playbackListener)
        bindMiniPlayer()
    }

    override fun onPause() {
        super.onPause()
        PlaybackCenter.removeListener(playbackListener)
        progressHandler.removeCallbacksAndMessages(null)
        PlaybackCenter.detachView(binding.miniVideo)
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackCenter.removeListener(playbackListener)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    private fun bindMiniPlayer() {
        if (isFinishing || isDestroyed) return
        val hasMedia = PlaybackCenter.hasMedia && !PlaybackCenter.inPip
        binding.miniPlayer.isVisible = hasMedia
        if (!hasMedia) {
            PlaybackCenter.detachView(binding.miniVideo)
            return
        }
        if (PlaybackCenter.mode == PlaybackCenter.Mode.VIDEO && !PlaybackCenter.audioOnlyMode) {
            setMiniMediaWidth(wide = true)
            binding.miniThumb.isVisible = false
            binding.miniVideo.isVisible = true
            PlaybackCenter.attachView(binding.miniVideo)
        } else {
            PlaybackCenter.detachView(binding.miniVideo)
            setMiniMediaWidth(wide = false)
            binding.miniVideo.isVisible = false
            binding.miniThumb.isVisible = true
        }
        updateMiniPlayer()
    }

    private fun setMiniMediaWidth(wide: Boolean) {
        val width = ((if (wide) 92 else 48) * resources.displayMetrics.density).toInt()
        val lp = binding.miniMedia.layoutParams
        if (lp.width != width) {
            lp.width = width
            binding.miniMedia.layoutParams = lp
        }
    }

    private fun updateMiniPlayer() {
        if (isFinishing || isDestroyed) return
        val entry = PlaybackCenter.currentEntry
        val show = entry != null && !PlaybackCenter.inPip
        binding.miniPlayer.isVisible = show
        if (entry == null) return

        binding.miniTitle.text = entry.title
        binding.miniTitle.isSelected = true
        binding.miniSubtitle.text = entry.uploader
        val thumbKey = "${entry.url}|${entry.thumbnailUrl}"
        if (thumbKey != lastMiniThumbKey) {
            lastMiniThumbKey = thumbKey
            Thumbs.load(binding.miniThumb, entry.thumbnailUrl)
        }
        binding.miniPlayPause.setImageResource(
            if (PlaybackCenter.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun openPlayerFromMini() {
        PlaybackCenter.currentEntry ?: return
        if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO || PlaybackCenter.audioOnlyMode) {
            NowPlayingActivity.start(this)
        } else {
            PlayerActivity.startResume(this)
        }
    }

    /**
     * Mini player gestures: swipe UP opens the full player, swipe LEFT/RIGHT dismisses it
     * (the card follows the finger). A plain tap still opens the player via the click listener.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installMiniPlayerGestures() {
        val density = resources.displayMetrics.density
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val openDist = 48 * density
        var downX = 0f
        var downY = 0f
        var horizontal = false
        binding.miniPlayer.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; horizontal = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!horizontal && kotlin.math.abs(dx) > slop * 2 && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        horizontal = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) {
                        v.translationX = dx
                        v.alpha = (1f - kotlin.math.abs(dx) / (v.width * 0.9f)).coerceIn(0.2f, 1f)
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    when {
                        horizontal && kotlin.math.abs(dx) > v.width * 0.35f &&
                            e.actionMasked == android.view.MotionEvent.ACTION_UP -> {
                            val dir = if (dx > 0) 1 else -1
                            v.animate().translationX(dir * v.width.toFloat()).alpha(0f)
                                .setDuration(160).withEndAction {
                                    PlaybackCenter.stopPlayback()
                                    v.translationX = 0f
                                    v.alpha = 1f
                                }.start()
                            true
                        }
                        horizontal -> {
                            v.animate().translationX(0f).alpha(1f).setDuration(180).start()
                            true
                        }
                        e.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            -dy > openDist && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            openPlayerFromMini()
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    private fun select(id: Int) {
        binding.floatingNav.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        if (id == selectedId) {
            (fragments[id] as? ScrollsToTop)?.scrollToTop()
            return
        }
        selectedId = id
        applySelection()
    }

    private fun applySelection() {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tx.setReorderingAllowed(true)
        if (AppPrefs.animations) {
            tx.setCustomAnimations(
                R.anim.tab_enter, R.anim.tab_exit,
                R.anim.tab_enter, R.anim.tab_exit
            )
        }
        fragments.forEach { (id, f) ->
            if (id == selectedId) return@forEach
            if (id in DETACH_WHEN_HIDDEN) {
                if (!f.isDetached) tx.detach(f)
            } else if (!f.isHidden) {
                tx.hide(f)
            }
        }
        val existing = fragments[selectedId]
        val target = existing ?: createFragment(selectedId).also {
            fragments[selectedId] = it
            tx.add(R.id.container, it, tagOf(selectedId))
        }
        if (existing != null && existing.isDetached) tx.attach(existing)
        tx.show(target)
        tx.commit()

        val isActive = { view: View -> view.id == selectedId }
        binding.navHome.isSelected = isActive(binding.navHome)
        binding.navMusic.isSelected = isActive(binding.navMusic)
        binding.navLibrary.isSelected = isActive(binding.navLibrary)
        binding.navMenu.isSelected = isActive(binding.navMenu)
        bounceSelectedNavIcon()
    }

    /** Small spring on the newly selected tab's icon (skipped when animations are off). */
    private fun bounceSelectedNavIcon() {
        if (!AppPrefs.animations || !com.sparktube.app.ui.music.Motion.animatorsEnabled()) return
        val tab = listOf(binding.navHome, binding.navMusic, binding.navLibrary, binding.navMenu)
            .firstOrNull { it.id == selectedId } as? android.view.ViewGroup ?: return
        val icon = tab.getChildAt(0) ?: return
        icon.animate().cancel()
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f
        icon.animate().scaleX(1f).scaleY(1f)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
            .setDuration(280).start()
    }

    private fun createFragment(id: Int): Fragment = when (id) {
        R.id.navHome -> HomeFragment()
        R.id.navMusic -> MusicFragment()
        R.id.navLibrary -> LibraryFragment()
        else -> MenuFragment()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedId != R.id.navHome) {
            select(R.id.navHome)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val STATE_SELECTED = "selected_nav"
        private const val REQ_NOTIFICATIONS = 4711
        private val DETACH_WHEN_HIDDEN = setOf(R.id.navMusic, R.id.navMenu)
        private val NAV_IDS = intArrayOf(R.id.navHome, R.id.navMusic, R.id.navLibrary, R.id.navMenu)
        private fun tagOf(id: Int) = "frag_$id"
    }
}
