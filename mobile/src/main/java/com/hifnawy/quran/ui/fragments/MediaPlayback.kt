package com.hifnawy.quran.ui.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.palette.graphics.Palette
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.FragmentMediaPlaybackBinding
import com.hifnawy.quran.shared.api.APIRequester
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.tools.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
import com.hifnawy.quran.ui.activities.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale
import com.hoko.blur.HokoBlur as Blur


/**
 * A simple [Fragment] subclass.
 */

class MediaPlayback : Fragment() {
    private lateinit var binding: FragmentMediaPlaybackBinding
    private lateinit var sharedPrefsManager: SharedPreferencesManager

    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null

    private val decimalFormat =
        DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))

    private val parentActivity: MainActivity by lazy {
        (activity as MainActivity)
    }

    private val serviceUpdatesBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)) {
                currentReciter =
                    intent.getSerializableExtra<Reciter>(Constants.IntentDataKeys.RECITER.name)
                currentChapter =
                    intent.getSerializableExtra<Chapter>(Constants.IntentDataKeys.CHAPTER.name)
                val durationMs = intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_DURATION.name, -1L)
                val currentPosition =
                    intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, -1L)

                if ((currentReciter != null) and (currentChapter != null)) {
                    updateUI(currentReciter!!, currentChapter!!)
                }

                if ((durationMs != -1L) and (currentPosition != -1L)) with(binding) {
                    chapterDuration.text = "${
                        getDuration(
                            currentPosition, (Duration.ofMillis(durationMs).toHours() > 0)
                        )
                    } \\ ${getDuration(durationMs, (Duration.ofMillis(durationMs).toHours() > 0))}"

                    if ((0 <= currentPosition) and (currentPosition <= durationMs) and !chapterSeek.isFocused) {
                        chapterSeek.valueFrom = 0f
                        chapterSeek.valueTo = durationMs.toFloat()
                        chapterSeek.value = currentPosition.toFloat()
                    }
                }
            } else if (intent.hasCategory(Constants.ServiceUpdates.ERROR.name)) {
                AlertDialog.Builder(this@MediaPlayback.context)
                    .setTitle(getString(R.string.connection_error_title))
                    .setMessage(getString(R.string.connection_error_message))
                    .setPositiveButton(getString(R.string.connection_error_action)) { _, _ ->
                        // navigate up twice to get back to reciters list fragment
                        findNavController().navigateUp()
                        findNavController().navigateUp()
                    }.create().apply {
                        window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
                        show()
                    }
            } else {
                // irrelevant
            }
        }
    }

    @SuppressLint("DiscouragedApi", "SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        Log.d(this::class.simpleName, "onCreate")

        binding = FragmentMediaPlaybackBinding.inflate(layoutInflater, container, false)
        sharedPrefsManager = SharedPreferencesManager(binding.root.context)

        with(binding) {
            chapterPlayPause.setOnClickListener {
                MediaService.instance?.run {
                    if (isMediaPlaying) {
                        pauseMedia()
                    } else {
                        resumeMedia()
                    }
                }
            }

            chapterNext.setOnClickListener {
                with(parentActivity) {
                    if (chapters.isEmpty()) {
                        getData {
                            currentChapter = currentChapter?.run {
                                chapters.single { chapter -> chapter.id == (if (id == 114) 1 else id + 1) }
                            }
                            currentChapter?.run { playChapter() }
                        }
                    } else {
                        currentChapter = currentChapter?.run {
                            chapters.single { chapter -> chapter.id == (if (id == 114) 1 else id + 1) }
                        }
                        currentChapter?.run { playChapter() }
                    }
                }
            }

            chapterPrevious.setOnClickListener {
                with(parentActivity) {
                    if (chapters.isEmpty()) {
                        getData {
                            currentChapter = currentChapter?.run {
                                chapters.single { chapter -> chapter.id == (if (id == 114) 1 else id - 1) }
                            }
                            currentChapter?.run { playChapter() }
                        }
                    } else {
                        currentChapter = currentChapter?.run {
                            chapters.single { chapter -> chapter.id == (if (id == 114) 1 else id - 1) }
                        }
                        currentChapter?.run { playChapter() }
                    }
                }
            }

            chapterSeek.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    chapterDuration.text = "${
                        getDuration(
                            value.toLong(),
                            (Duration.ofMillis(chapterSeek.valueTo.toLong()).toHours() > 0)
                        )
                    } \\ ${
                        getDuration(
                            chapterSeek.valueTo.toLong(),
                            (Duration.ofMillis(chapterSeek.valueTo.toLong()).toHours() > 0)
                        )
                    }"

                    MediaService.instance?.seekChapterToPosition(value.toLong())
                }
            }
        }

        return binding.root
    }

    override fun onResume() {
        Log.d(this::class.simpleName, "onResume")

        binding.downloadDialog.visibility = View.GONE

        with(parentActivity) {
            supportActionBar?.hide()

            registerReceiver(serviceUpdatesBroadcastReceiver,
                IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_updates)).apply {
                    addCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)
                    addCategory(Constants.ServiceUpdates.ERROR.name)
                })
        }

        with(MediaPlaybackArgs.fromBundle(requireArguments())) {
            currentReciter = reciter
            currentChapter = chapter
        }

        playChapter()

        super.onResume()
    }

    override fun onDestroy() {
        with(parentActivity) {
            try {
                unregisterReceiver(serviceUpdatesBroadcastReceiver)
            } catch (_: IllegalArgumentException) {
                Log.w(
                    this@MediaPlayback::class.simpleName,
                    "Could not unregister ${::serviceUpdatesBroadcastReceiver.name}, it was probably unregistered in an earlier stage!!!"
                )
            }

            parentActivity.supportActionBar?.show()
        }

        super.onDestroy()
    }

    private fun playChapter() {
        if ((currentReciter == null) || (currentChapter == null)) return

        updateUI(currentReciter!!, currentChapter!!)

        sharedPrefsManager.getChapterPath(currentReciter!!, currentChapter!!)?.let {
            MediaService.instance?.run {
                playMedia(currentReciter, currentChapter)
            } ?: MediaService.initialize(
                parentActivity, currentReciter, currentChapter
            )
        } ?: downloadChapter()
    }

    @SuppressLint("SetTextI18n")
    private fun downloadChapter() {
        with(binding) {
            downloadDialog.visibility = View.VISIBLE
            downloadDialogChapterDownloadMessage.text = "${
                context?.getString(
                    com.hifnawy.quran.shared.R.string.loading_chapter, currentChapter?.name_arabic
                )
            }\n${decimalFormat.format(0)} مب. \\ ${decimalFormat.format(0)} مب. (${
                decimalFormat.format(
                    0
                )
            }٪)"
            downloadDialogChapterProgress.value = 0f
        }

        parentActivity.unregisterReceiver(serviceUpdatesBroadcastReceiver)

        lifecycleScope.launch {
            val chapterAudioFile = lifecycleScope.async(Dispatchers.IO) {
                APIRequester.getChapterAudioFile(
                    currentReciter!!.id, currentChapter!!.id
                )
            }.await()
            lifecycleScope.async(Dispatchers.IO) {
                Utilities.downloadFile(
                    requireContext(),
                    URL(chapterAudioFile?.audio_url),
                    currentReciter!!,
                    currentChapter!!,
                    ::updateDownloadUI
                )
            }.await()
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun updateDownloadUI(
        downloadStatus: Utilities.Companion.DownloadStatus,
        bytesDownloaded: Long,
        fileSize: Int,
        percentage: Float,
        audioFile: File?
    ) {
        withContext(Dispatchers.Main) {
            when (downloadStatus) {
                Utilities.Companion.DownloadStatus.STARTING_DOWNLOAD -> binding.downloadDialog.visibility =
                    View.VISIBLE

                Utilities.Companion.DownloadStatus.DOWNLOADING -> {
                    with(binding) {
                        downloadDialogChapterDownloadMessage.text = "${
                            context?.getString(
                                com.hifnawy.quran.shared.R.string.loading_chapter,
                                currentChapter?.name_arabic
                            )
                        }\n${decimalFormat.format(bytesDownloaded / (1024 * 1024))} مب. \\ ${
                            decimalFormat.format(
                                fileSize / (1024 * 1024)
                            )
                        } مب. (${
                            decimalFormat.format(
                                percentage
                            )
                        }٪)"
                        downloadDialogChapterProgress.value = percentage
                    }
                }

                Utilities.Companion.DownloadStatus.FINISHED_DOWNLOAD -> {
                    binding.downloadDialog.visibility = View.GONE

                    parentActivity.registerReceiver(serviceUpdatesBroadcastReceiver,
                        IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_updates)).apply {
                            addCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)
                            addCategory(Constants.ServiceUpdates.ERROR.name)
                        })

                    MediaService.instance?.run {
                        playMedia(currentReciter, currentChapter)
                    } ?: MediaService.initialize(
                        parentActivity, currentReciter, currentChapter
                    )
                }
            }
        }
    }

    private fun updateUI(reciter: Reciter, chapter: Chapter) {
        @SuppressLint("DiscouragedApi") val drawableId = resources.getIdentifier(
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
            downloadDialogChapterProgress.valueFrom = 0f
            downloadDialogChapterProgress.valueTo = 100f
            chapterBackgroundImage.setImageDrawable(bitmap.toDrawable(resources))
            chapterName.text = chapter.name_arabic
            reciterName.text = reciter.name_ar
            chapterImageCard.setCardBackgroundColor(
                if (chapter.id % 2 == 0) Color.parseColor("#336e6a") else Color.parseColor(
                    "#dd5f56"
                )
            )
            chapterImage.setImageDrawable(AppCompatResources.getDrawable(requireContext(), drawableId))
            chapterSeek.trackActiveTintList = ColorStateList.valueOf(dominantColor)
            // chapterSeek.thumbTintList = ColorStateList.valueOf(dominantColor)
            MediaService.instance?.run {
                chapterPlayPause.icon = if (isMediaPlaying) AppCompatResources.getDrawable(
                    requireContext(), com.hifnawy.quran.shared.R.drawable.media_pause_black
                ) else AppCompatResources.getDrawable(
                    requireContext(), com.hifnawy.quran.shared.R.drawable.media_play_black
                )
            }
            chapterPlayPause.setBackgroundColor(dominantColor)
            chapterNext.setBackgroundColor(dominantColor)
            chapterPrevious.setBackgroundColor(dominantColor)
        }
    }

    private fun getDuration(durationMs: Long, showHours: Boolean): String {
        val duration: Duration = Duration.ofMillis(durationMs)

        val durationS = duration.seconds
        val hours = durationS / 3600
        val minutes = (durationS % 3600) / 60
        val seconds = durationS % 60

        val numberFormat = NumberFormat.getInstance(Locale("ar", "EG"))
        val hoursString = numberFormat.format(hours).padStart(2, '۰')
        val minutesString = numberFormat.format(minutes).padStart(2, '۰')
        val secondsString = numberFormat.format(seconds).padStart(2, '۰')

        return "$secondsString:$minutesString${if (hours > 0) ":$hoursString" else if (showHours) ":۰۰" else ""}"
    }
}