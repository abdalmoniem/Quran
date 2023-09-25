package com.hifnawy.quran.ui.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.hifnawy.quran.databinding.FragmentQuranMediaPlaybackBinding
import com.hifnawy.quran.shared.QuranMediaService

/**
 * A simple [Fragment] subclass.
 */
class QuranMediaPlayback : Fragment() {

    private lateinit var binding: FragmentQuranMediaPlaybackBinding

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentQuranMediaPlaybackBinding.inflate(layoutInflater, container, false)
        val bundle = QuranMediaPlaybackArgs.fromBundle(requireArguments())
        val reciter = bundle.reciter
        val chapter = bundle.chapter

        val drawableId = resources.getIdentifier(
            "chapter_${chapter.id.toString().padStart(3, '0')}",
            "drawable",
            requireContext().packageName
        )

        binding.chapterName.text = chapter.name_arabic
        binding.reciterName.text =
            if (reciter.translated_name != null) reciter.translated_name!!.name else reciter.reciter_name
        binding.chapterImage.setImageDrawable(resources.getDrawable(drawableId))
        requireActivity().startService(
            Intent(
                context,
                QuranMediaService::class.java
            ).apply {
                putExtra("RECITER_ID", bundle.reciter)
                putExtra("CHAPTER_ID", bundle.chapter)
            }
        )

        return binding.root
    }
}