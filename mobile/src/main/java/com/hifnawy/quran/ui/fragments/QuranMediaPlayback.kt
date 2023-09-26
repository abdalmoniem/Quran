package com.hifnawy.quran.ui.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.registerReceiver
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import com.hifnawy.quran.databinding.FragmentQuranMediaPlaybackBinding
import com.hifnawy.quran.shared.QuranMediaService
import com.hifnawy.quran.ui.activities.MainActivity
import java.time.Duration
import com.hoko.blur.HokoBlur as Blur


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
        val broadcastReceiver = object : BroadcastReceiver() {
            @SuppressLint("SetTextI18n")
            override fun onReceive(context: Context, intent: Intent) {
                val durationMs = intent.extras!!.getLong("duration")
                val currentPosition = intent.extras!!.getLong("currentPosition")

                with(binding) {
                    chapterDuration.text =
                        "${getDuration(currentPosition, true)} / ${getDuration(durationMs, true)}"
                    chapterSeek.valueFrom = 0f
                    chapterSeek.valueTo = durationMs.toFloat()
                    chapterSeek.value = currentPosition.toFloat()
                }
            }
        }

        registerReceiver(
            requireContext(),
            broadcastReceiver,
            IntentFilter("your_action_name"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        binding = FragmentQuranMediaPlaybackBinding.inflate(layoutInflater, container, false)
        val bundle = QuranMediaPlaybackArgs.fromBundle(requireArguments())
        val reciter = bundle.reciter
        val chapter = bundle.chapter

        val drawableId = resources.getIdentifier(
            "chapter_${chapter.id.toString().padStart(3, '0')}",
            "drawable",
            requireContext().packageName
        )

        val bitmap = Blur.with(context)
            .scheme(Blur.SCHEME_NATIVE) //different implementation, RenderScript、OpenGL、Native(default) and Java
            .mode(Blur.MODE_GAUSSIAN) //blur algorithms，Gaussian、Stack(default) and Box
            .radius(5) //blur radius，max=25，default=5
            .sampleFactor(2.0f)
            .processor()
            .blur(
                (AppCompatResources.getDrawable(
                    requireContext(),
                    drawableId
                ) as BitmapDrawable).bitmap
            )

        val dominantColor = Palette.from(
            (AppCompatResources.getDrawable(
                requireContext(),
                drawableId
            ) as BitmapDrawable).bitmap
        ).generate().getDominantColor(Color.RED)

        with(binding) {
            rootLayout.background = bitmap.toDrawable(resources)
            chapterName.text = chapter.name_arabic
            reciterName.text =
                if (reciter.translated_name != null) reciter.translated_name!!.name else reciter.reciter_name
            chapterImage.setImageDrawable(
                AppCompatResources.getDrawable(requireContext(), drawableId)
            )
            chapterPlay.setBackgroundColor(dominantColor)
            chapterNext.setBackgroundColor(dominantColor)
            chapterPrevious.setBackgroundColor(dominantColor)
            chapterSeek.trackActiveTintList = ColorStateList.valueOf(dominantColor)
            chapterSeek.thumbTintList = ColorStateList.valueOf(dominantColor)
        }
        (activity as MainActivity).startForegroundService(Intent(
            context,
            QuranMediaService::class.java
        ).apply {
            putExtra("RECITER_ID", bundle.reciter)
            putExtra("CHAPTER_ID", bundle.chapter)
        })

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        (activity as MainActivity).supportActionBar?.hide()
    }

    override fun onStop() {
        super.onStop()
        (activity as MainActivity).supportActionBar?.show()
    }

    private fun getDuration(durationMs: Long, getHoursPart: Boolean): String {
        val duration: Duration = Duration.ofMillis(durationMs)

        val durationS = duration.seconds
        val hours = durationS / 3600
        val minutes = (durationS % 3600) / 60
        val seconds = durationS % 60

        return "${
            if (hours > 0) "${
                hours.toString().padStart(2, '0')
            }:" else if (getHoursPart) "00:" else ""
        }${
            minutes.toString().padStart(2, '0')
        }:${seconds.toString().padStart(2, '0')}"
    }
}