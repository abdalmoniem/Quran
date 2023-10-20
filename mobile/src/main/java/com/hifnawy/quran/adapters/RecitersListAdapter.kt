package com.hifnawy.quran.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ReciterItemBinding
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager

class RecitersListAdapter(
        private val context: Context,
        private var reciters: ArrayList<Reciter>,
        private val itemClickListener: ReciterClickListener? = null
) : RecyclerView.Adapter<RecitersListAdapter.ReciterViewHolder>() {

    private lateinit var recyclerView: RecyclerView
    private var mLastViewHolderPosition = -1
    private val sharedPrefsManager by lazy { SharedPreferencesManager(context) }

    fun interface ReciterClickListener {

        fun reciterItemClickListener(position: Int, reciter: Reciter, itemView: ReciterViewHolder)
    }

    inner class ReciterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val binding = ReciterItemBinding.bind(itemView)
        val reciterName = binding.reciterName
        val recitationStyle = binding.recitationStyle
        val cardHolder = binding.cardHolder
        val audioIndicator = binding.audioIndicator
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReciterViewHolder {
        return ReciterViewHolder(
                LayoutInflater.from(context).inflate(R.layout.reciter_item, parent, false)
        )
    }

    override fun getItemCount(): Int {
        return this.reciters.size
    }

    override fun onBindViewHolder(holder: ReciterViewHolder, position: Int) {
        with(holder) {
            val reciter = this@RecitersListAdapter.reciters[position]

            reciterName.text = reciter.name_ar
            recitationStyle.text = if (reciter.style != null) reciter.style!!.style else "تلاوة"

            sharedPrefsManager.lastReciter?.let {
                audioIndicator.visibility =
                    if ((reciter.id == it.id) && MediaService.isMediaPlaying) View.VISIBLE else View.GONE
            }

            cardHolder.setOnClickListener {
                itemClickListener?.reciterItemClickListener(position, reciter, holder)
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

    fun setReciters(reciters: List<Reciter>) {
        this.reciters = ArrayList(reciters)

        notifyDataSetChanged()
        // notifyItemInserted(this.reciters.lastIndex)
    }

    fun getReciters(): ArrayList<Reciter> = reciters

    fun clear() {
        this.reciters.clear()
    }
}