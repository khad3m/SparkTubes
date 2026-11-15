package com.sparktube.app.ui.shorts

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.sparktube.app.R
import com.sparktube.app.databinding.ActivityShortsBinding
import com.sparktube.app.data.YtRepository
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.ui.music.Motion
import com.sparktube.app.util.AppPrefs
import com.sparktube.app.util.Themes
import com.sparktube.app.util.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shorts / Reels screen (v1.7.0): a vertical full-page snap feed of
 * short-form videos (≤ 3 minutes). Low-RAM friendly — thumbnails only, the
 * actual playback hands off to the normal watch page. Enabled from
 * Settings → "Shorts / Reels videos"; also surfaced as a Search tab there.
 */
class ShortsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShortsBinding
    private lateinit var adapter: ShortsAdapter
    private val shorts = mutableListOf<com.sparktube.app.ui.common.VideoUiModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityShortsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        adapter = ShortsAdapter(shorts) { model ->
            performHaptic()
            PlayerActivity.start(this, model)
        }
        binding.recycler.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.recycler.adapter = adapter
        PagerSnapHelper().attachToRecyclerView(binding.recycler)

        load()
    }

    override fun onResume() {
        super.onResume()
        if (Themes.recreateIfNeeded(this)) return
    }

    private fun performHaptic() {
        binding.recycler.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun load() {
        binding.emptyView.isVisible = false
        binding.progress.isVisible = true
        lifecycleScope.launch {
            try {
                val items = fetchShorts()
                shorts.clear()
                shorts.addAll(items)
                adapter.notifyDataSetChanged()
                binding.progress.isVisible = false
                binding.emptyView.isVisible = shorts.isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.progress.isVisible = false
                binding.emptyView.isVisible = shorts.isEmpty()
                Toast.makeText(this@ShortsActivity, R.string.shorts_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Trending first (shorts only), topped up with a search when too few. */
    private suspend fun fetchShorts(): List<com.sparktube.app.ui.common.VideoUiModel> {
        val out = LinkedHashMap<String, com.sparktube.app.ui.common.VideoUiModel>()
        runCatching {
            YtRepository.trending(AppPrefs.countryOrDefault)
        }.getOrDefault(emptyList())
            .filter { it.duration in 1L..180L }
            .forEach { item ->
                val model = item.toUiModel()
                if (model.url.isNotBlank()) out[model.url] = model
            }
        if (out.size < 12) {
            runCatching {
                YtRepository.searchVideos("trending shorts clips", null).items
            }.getOrDefault(emptyList())
                .filter { it.duration in 1L..180L }
                .forEach { item ->
                    val model = item.toUiModel()
                    if (model.url.isNotBlank()) out.putIfAbsent(model.url, model)
                }
        }
        return out.values.toList()
    }

    /** One full-page shorts card: cover art, gradient scrim, title + channel. */
    private class CardView(context: Context) : android.widget.FrameLayout(context) {

        val cover: ShapeableImageView
        val title: TextView
        val subtitle: TextView
        val duration: TextView

        init {
            val d = context.resources.displayMetrics.density
            fun px(v: Int) = (v * d).toInt()

            cover = ShapeableImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(px(20).toFloat())
                    .build()
            }
            addView(cover)

            val scrim = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xE6000000.toInt(), 0x00000000)
                )
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(190), Gravity.BOTTOM
                )
            }
            addView(scrim)

            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
                ).also { it.setMargins(px(18), 0, px(18), px(18)) }
            }

            duration = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = px(9).toFloat()
                    setColor(0x88FF0033.toInt())
                }
                setPadding(px(10), px(4), px(10), px(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            info.addView(duration)

            title = TextView(context).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = px(8) }
            }
            info.addView(title)

            subtitle = TextView(context).apply {
                textSize = 13f
                setTextColor(0xCCFFFFFF.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = px(4) }
            }
            info.addView(subtitle)
            addView(info)

            Motion.pressable(this, 0.97f)
        }
    }

    private class ShortsAdapter(
        private val items: List<com.sparktube.app.ui.common.VideoUiModel>,
        private val onClick: (com.sparktube.app.ui.common.VideoUiModel) -> Unit
    ) : RecyclerView.Adapter<ShortsAdapter.VH>() {

        class VH(val card: CardView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val d = parent.resources.displayMetrics.density
            // One card ≈ 72% of the screen height: a real full-page shorts feel
            // on every device without ever measuring a half-laid-out screen.
            val pageHeight = (parent.context.resources.displayMetrics.heightPixels * 0.72f).toInt()
            val card = CardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, pageHeight
                ).also {
                    it.topMargin = (10 * d).toInt()
                    it.bottomMargin = (10 * d).toInt()
                }
                elevation = (6 * d).toFloat()
            }
            return VH(card)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val model = items[position]
            val card = holder.card
            Thumbs.load(card.cover, model.thumbnailUrl)
            card.title.text = model.title
            card.subtitle.text = model.uploader
            card.duration.text = model.durationLabel
            card.contentDescription = "${model.title}, ${model.uploader}"
            card.setOnClickListener { onClick(model) }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ShortsActivity::class.java))
        }
    }
}
