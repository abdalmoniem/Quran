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
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import com.hifnawy.quran.databinding.FragmentQuranMediaPlaybackBinding
import com.hifnawy.quran.shared.QuranMediaService
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
import com.hifnawy.quran.ui.activities.MainActivity
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale
import com.hoko.blur.HokoBlur as Blur


/**
 * A simple [Fragment] subclass.
 */
class QuranMediaPlayback : Fragment() {
    private lateinit var binding: FragmentQuranMediaPlaybackBinding

    private lateinit var reciter: Reciter
    private lateinit var chapter: Chapter
    private val numberFormat = NumberFormat.getInstance(Locale("ar", "EG"))

    private val serviceUpdatesBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context, intent: Intent) {
            val durationMs = intent.getLongExtra("DURATION", -1L)
            val currentPosition = intent.getLongExtra("CURRENT_POSITION", -1L)

            val reciter = intent.getSerializableExtra<Reciter>("RECITER")
            val chapter = intent.getSerializableExtra<Chapter>("CHAPTER")

            if ((reciter != null) and (chapter != null)) {
                updateUI(reciter!!, chapter!!)
            }

            if ((durationMs != -1L) and (currentPosition != -1L)) with(binding) {
                chapterDuration.text =
                    "${getDuration(currentPosition, true)} / ${getDuration(durationMs, true)}"

                if ((0 <= currentPosition) and (currentPosition <= durationMs) and !chapterSeek.isFocused) {
                    chapterSeek.valueFrom = 0f
                    chapterSeek.valueTo = durationMs.toFloat()
                    chapterSeek.value = currentPosition.toFloat()
                }
            }
        }
    }

    private val downloadUpdatesBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context, intent: Intent) {
            with(intent) {
                val downloadStatus = getStringExtra("DOWNLOAD_STATUS")
                val bytesDownloaded = getLongExtra("BYTES_DOWNLOADED", -1L)
                val fileSize = getIntExtra("FILE_SIZE", -1)
                val percentage = getFloatExtra("PERCENTAGE", -1.0f)

                if (!downloadStatus.isNullOrEmpty() && (bytesDownloaded != -1L) && (fileSize != -1) && (percentage != -1.0f)) {
                    when (downloadStatus) {
                        "DOWNLOADING" -> {
                            with(binding) {
                                if (downloadDialog.visibility != View.VISIBLE) {
                                    downloadDialog.visibility = View.VISIBLE
                                }

                                downloadDialogMessage.text = "${
                                    this@QuranMediaPlayback.getString(
                                        com.hifnawy.quran.shared.R.string.loading_chapter,
                                        chapter.name_arabic
                                    )
                                }\n${numberFormat.format(bytesDownloaded / (1024 * 1024))} مب. / ${
                                    numberFormat.format(
                                        fileSize / (1024 * 1024)
                                    )
                                } مب. (${
                                    numberFormat.format(
                                        percentage
                                    )
                                })%"
                                downloadDialogProgress.value = percentage
                            }
                            Log.d(
                                "Quran_Media_Download",
                                "downloading ${chapter.name_simple} $bytesDownloaded / $fileSize ($percentage%)"
                            )
                        }

                        "DOWNLOADED" -> {
                            binding.downloadDialog.visibility = View.GONE
                        }

                        else -> {

                        }
                    }
                } else {

                }
            }
        }
    }

    @SuppressLint("DiscouragedApi", "SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentQuranMediaPlaybackBinding.inflate(layoutInflater, container, false)
        val bundle = QuranMediaPlaybackArgs.fromBundle(requireArguments())
        reciter = bundle.reciter
        chapter = bundle.chapter

        updateUI(reciter, chapter)

        with(binding) {
            chapterPlay.setOnClickListener {
                (activity as MainActivity).sendBroadcast(Intent(getString(com.hifnawy.quran.shared.R.string.quran_media_player_controls)).apply {
                    putExtra("PLAY_PAUSE", "PLAY")
                })
            }

            chapterNext.setOnClickListener {
                (activity as MainActivity).sendBroadcast(Intent(getString(com.hifnawy.quran.shared.R.string.quran_media_player_controls)).apply {
                    putExtra("NEXT", "NEXT")
                })
            }

            chapterPrevious.setOnClickListener {
                (activity as MainActivity).sendBroadcast(Intent(getString(com.hifnawy.quran.shared.R.string.quran_media_player_controls)).apply {
                    putExtra("PREVIOUS", "PREVIOUS")
                })
            }

            chapterSeek.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    chapterDuration.text = "${
                        getDuration(
                            value.toLong(), true
                        )
                    } / ${getDuration(chapterSeek.valueTo.toLong(), true)}"

                    (activity as MainActivity).sendBroadcast(Intent(getString(com.hifnawy.quran.shared.R.string.quran_media_player_controls)).apply {
                        putExtra("POSITION", value.toLong().toString())
                    })
                }
            }
        }

        if (!QuranMediaService.isRunning) {
            (activity as MainActivity).startForegroundService(Intent(
                context, QuranMediaService::class.java
            ).apply {
                putExtra("RECITER", bundle.reciter)
                putExtra("CHAPTER", bundle.chapter)
            })
        } else {
            (activity as MainActivity).sendBroadcast(Intent(getString(com.hifnawy.quran.shared.R.string.quran_media_player_controls)).apply {
                putExtra("RECITER", reciter)
                putExtra("CHAPTER", chapter)
            })
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        (activity as MainActivity).supportActionBar?.hide()
        (activity as MainActivity).registerReceiver(
            serviceUpdatesBroadcastReceiver,
            IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_updates)),
        )

        (activity as MainActivity).registerReceiver(
            downloadUpdatesBroadcastReceiver,
            IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_file_download_updates)),
        )
    }

    override fun onPause() {
        super.onPause()
        (activity as MainActivity).unregisterReceiver(serviceUpdatesBroadcastReceiver)
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

    @SuppressLint("DiscouragedApi")
    private fun updateUI(reciter: Reciter, chapter: Chapter) {
        val drawableId = resources.getIdentifier(
            "chapter_${chapter.id.toString().padStart(3, '0')}", "drawable", requireContext().packageName
        )

        val bitmap = Blur.with(context)
            .scheme(Blur.SCHEME_NATIVE) //different implementation, RenderScript、OpenGL、Native(default) and Java
            .mode(Blur.MODE_GAUSSIAN) //blur algorithms，Gaussian、Stack(default) and Box
            .radius(3) //blur radius，max=25，default=5
            .sampleFactor(2.0f).processor().blur(
                (AppCompatResources.getDrawable(
                    requireContext(), drawableId
                ) as BitmapDrawable).bitmap
            )

        val dominantColor = Palette.from(
            (AppCompatResources.getDrawable(
                requireContext(), drawableId
            ) as BitmapDrawable).bitmap
        ).generate().getDominantColor(Color.RED)

        with(binding) {
            downloadDialog.visibility = View.GONE
            downloadDialogProgress.valueFrom = 0f
            downloadDialogProgress.valueTo = 100f
            chapterBackgroundImage.setImageDrawable(bitmap.toDrawable(resources))
            chapterName.text = chapter.name_arabic
            reciterName.text =
                if (reciter.translated_name != null) reciter.translated_name!!.name else reciter.reciter_name
            chapterImageCard.setCardBackgroundColor(
                if (chapter.id % 2 == 0) Color.parseColor("#336e6a") else Color.parseColor(
                    "#dd5f56"
                )
            )
            chapterImage.setImageDrawable(AppCompatResources.getDrawable(requireContext(), drawableId))
            chapterSeek.trackActiveTintList = ColorStateList.valueOf(dominantColor)
            chapterSeek.thumbTintList = ColorStateList.valueOf(dominantColor)
            if (QuranMediaService.isRunning) {
                chapterPlay.icon = if (QuranMediaService.isMediaPlaying) AppCompatResources.getDrawable(
                    requireContext(), com.hifnawy.quran.shared.R.drawable.media_pause_black
                ) else AppCompatResources.getDrawable(
                    requireContext(), com.hifnawy.quran.shared.R.drawable.media_play_black
                )
            }
            chapterPlay.setBackgroundColor(dominantColor)
            chapterNext.setBackgroundColor(dominantColor)
            chapterPrevious.setBackgroundColor(dominantColor)
        }
    }
}