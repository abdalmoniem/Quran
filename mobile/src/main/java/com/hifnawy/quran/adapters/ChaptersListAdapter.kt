package com.hifnawy.quran.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ChapterItemBinding
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import java.text.NumberFormat
import java.util.Locale

class ChaptersListAdapter(
        private val context: Context,
        private var chapters: ArrayList<Chapter>,
        private val itemClickListener: ChapterClickListener? = null
) : RecyclerView.Adapter<ChaptersListAdapter.ChapterViewHolder>() {

    private val sharedPrefsManager by lazy { SharedPreferencesManager(context) }
    private lateinit var recyclerView: RecyclerView
    private var mLastViewHolderPosition = -1

    fun interface ChapterClickListener {

        fun chapterItemClickListener(position: Int, chapter: Chapter, itemView: ChapterViewHolder)
    }

    inner class ChapterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val binding = ChapterItemBinding.bind(itemView)
        val chapterName = binding.chapterName
        val verseCount = binding.chapterVerseCount
        val revelationPlace = binding.chapterRevelationPlace
        val revelationOrder = binding.chapterRevelationOrder
        val cardHolder = binding.cardHolder
        val audioIndicator = binding.audioIndicator
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        return ChapterViewHolder(
                LayoutInflater.from(context).inflate(R.layout.chapter_item, parent, false)
        )
    }

    override fun getItemCount(): Int {
        return this.chapters.size
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        with(holder) {
            val chapter = this@ChaptersListAdapter.chapters[position]
            val nf = NumberFormat.getInstance(Locale("ar", "EG"))

            chapterName.text = chapter.name_arabic
            verseCount.text = context.getString(R.string.verse_count, nf.format(chapter.verses_count))
            revelationPlace.text = chapter.revelation_place.place
            revelationOrder.text =
                context.getString(R.string.revelation_order, nf.format(chapter.revelation_order))

            sharedPrefsManager.lastChapter?.let {
                audioIndicator.visibility =
                    if ((chapter.id == it.id) && MediaService.isMediaPlaying) View.VISIBLE else View.GONE
            }

            cardHolder.setOnClickListener {
                itemClickListener?.chapterItemClickListener(position, chapter, holder)
            }
        }

        setAnimation(holder.itemView, position)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView

        this.recyclerView.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_fall_down)
    }

    private fun setAnimation(itemView: View, position: Int) {
        val animation: Animation = if (position < mLastViewHolderPosition) {
            AnimationUtils.loadAnimation(context, R.anim.single_item_animation_rise_up)
        } else {
            AnimationUtils.loadAnimation(context, R.anim.single_item_animation_fall_down)
        }
        itemView.startAnimation(animation)
        mLastViewHolderPosition = position
    }

    fun setChapters(chapters: List<Chapter>) {
        this.chapters = ArrayList(chapters)

        notifyDataSetChanged()
        // notifyItemInserted(this.chapters.lastIndex)
    }

    fun getChapters(): ArrayList<Chapter> = chapters

    fun clear() {
        this.chapters.clear()
    }
}