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
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.FragmentMediaPlaybackBinding
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities
import com.hifnawy.quran.ui.activities.MainActivity
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
class MediaPlayback(
        private var reciter: Reciter,
        private var chapter: Chapter,
        private var chapterPosition: Long = 0L
) : Fragment() {

    private val parentActivity: MainActivity by lazy { (activity as MainActivity) }
    private val mediaService by lazy { parentActivity.mediaService }
    private val decimalFormat =
        DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))
    private val mediaUpdatesReceiver = MediaUpdatesReceiver()
    private lateinit var binding: FragmentMediaPlaybackBinding
    private lateinit var sharedPrefsManager: SharedPreferencesManager

    @SuppressLint("DiscouragedApi", "SetTextI18n")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentMediaPlaybackBinding.inflate(layoutInflater, container, false)
        sharedPrefsManager = SharedPreferencesManager(binding.root.context)

        with(binding) {
            chapterPlayPause.setOnClickListener {
                with(mediaService) {
                    if (isMediaPlaying) {
                        pauseMedia()
                    } else {
                        resumeMedia()
                    }
                }
            }

            chapterNext.setOnClickListener {
                with(parentActivity) {
                    val nextChapterId = if (chapter.id == 114) 1 else chapter.id + 1
                    chapter = chapters.single { chapter -> chapter.id == nextChapterId }

                    playChapter(chapter, 0L)
                }
            }

            chapterPrevious.setOnClickListener {
                with(parentActivity) {
                    val nextChapterId = if (chapter.id == 114) 1 else chapter.id - 1
                    chapter = chapters.single { chapter -> chapter.id == nextChapterId }

                    playChapter(chapter, 0L)
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

                    mediaService.seekChapterToPosition(value.toLong())
                }
            }
        }

        with(parentActivity) {
            supportActionBar?.hide()

            registerReceiver(mediaUpdatesReceiver,
                             IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_updates)).apply {
                                 addCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)
                             })
        }

        playChapter(chapter)

        return binding.root
    }

    override fun onDestroy() {
        with(parentActivity) {
            try {
                unregisterReceiver(mediaUpdatesReceiver)
            } catch (_: IllegalArgumentException) {
                Log.w(
                        this@MediaPlayback::class.simpleName,
                        "Could not unregister ${::mediaUpdatesReceiver.name}, it was probably unregistered in an earlier stage!!!"
                )
            }

            parentActivity.supportActionBar?.show()
        }

        super.onDestroy()
    }

    private fun playChapter(chapter: Chapter, startPosition: Long = chapterPosition) {
        updateUI(reciter, chapter)

        sharedPrefsManager.getChapterPath(reciter, chapter)?.let {
            mediaService.prepareMedia(reciter, chapter, startPosition)
        } ?: downloadChapter()
    }

    @SuppressLint("SetTextI18n")
    private fun downloadChapter() {
        val (dialog, dialogBinding) = DialogBuilder.prepareDownloadDialog(
                binding.root.context,
                DialogBuilder.DownloadType.SINGLE
        )

        dialog.show()
        with(dialogBinding) {
            downloadDialogChapterProgress.min = 0
            downloadDialogChapterProgress.max = 100
            downloadDialogChapterProgress.progress = 0
            downloadDialogChapterDownloadMessage.text = "${
                context?.getString(
                        com.hifnawy.quran.shared.R.string.loading_chapter, chapter.name_arabic
                )
            }\n${decimalFormat.format(0)} مب. \\ ${decimalFormat.format(0)} مب. (${
                decimalFormat.format(
                        0
                )
            }٪)"
        }

        parentActivity.unregisterReceiver(mediaUpdatesReceiver)

        lifecycleScope.launch {
            val chapterAudioFile = lifecycleScope.async(Dispatchers.IO) {
                QuranAPI.getChapterAudioFile(
                        reciter.id, chapter.id
                )
            }.await()
            lifecycleScope.async(Dispatchers.IO) {
                with(dialogBinding) {
                    Utilities.downloadFile(
                            binding.root.context,
                            URL(chapterAudioFile?.audio_url),
                            reciter,
                            chapter
                    ) { downloadStatus, bytesDownloaded, fileSize, percentage, _ ->
                        withContext(Dispatchers.Main) {
                            when (downloadStatus) {
                                Utilities.Companion.DownloadStatus.STARTING_DOWNLOAD -> Unit
                                Utilities.Companion.DownloadStatus.DOWNLOADING -> {
                                    downloadDialogChapterProgress.progress = percentage.toInt()
                                    downloadDialogChapterDownloadMessage.text = "${
                                        context?.getString(
                                                com.hifnawy.quran.shared.R.string.loading_chapter,
                                                chapter.name_arabic
                                        )
                                    }\n${decimalFormat.format(bytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                        decimalFormat.format(
                                                fileSize.toFloat() / (1024 * 1024)
                                        )
                                    } مب. (${
                                        decimalFormat.format(
                                                percentage
                                        )
                                    }٪)"

                                }

                                Utilities.Companion.DownloadStatus.FINISHED_DOWNLOAD -> {
                                    dialog.dismiss()

                                    parentActivity.registerReceiver(mediaUpdatesReceiver,
                                                                    IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_updates)).apply {
                                                                        addCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)
                                                                    })

                                    mediaService.prepareMedia(reciter, chapter)
                                }

                                Utilities.Companion.DownloadStatus.DOWNLOAD_ERROR -> {
                                    dialog.dismiss()

                                    DialogBuilder.showErrorDialog(
                                            this@MediaPlayback.binding.root.context,
                                            getString(R.string.connection_error_title),
                                            getString(R.string.connection_error_message),
                                            getString(R.string.connection_error_action)
                                    ) { _, _ ->
                                        parentFragmentManager.beginTransaction()
                                            .replace(
                                                    parentActivity.binding.fragmentContainer.id,
                                                    RecitersList()
                                            )
                                            .commit()
                                    }
                                }
                            }
                        }
                    }
                }
            }.await()
        }
    }

    private fun updateUI(reciter: Reciter, chapter: Chapter) {
        @SuppressLint("DiscouragedApi") val drawableId = resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}",
                "drawable",
                binding.root.context.packageName
        )
        val bitmap = Blur.with(context)
            .scheme(Blur.SCHEME_NATIVE) // different implementation, RenderScript、OpenGL、Native(default) and Java
            .mode(Blur.MODE_GAUSSIAN) // blur algorithms，Gaussian、Stack(default) and Box
            .radius(3) // blur radius，max=25，default=5
            .sampleFactor(2.0f).processor().blur(
                    (AppCompatResources.getDrawable(
                            binding.root.context, drawableId
                    ) as BitmapDrawable).bitmap
            )
        val dominantColor = Palette.from(
                (AppCompatResources.getDrawable(
                        binding.root.context, drawableId
                ) as BitmapDrawable).bitmap
        ).generate().getDominantColor(Color.RED)

        with(binding) {
            chapterBackgroundImage.setImageDrawable(bitmap.toDrawable(resources))
            chapterName.text = chapter.name_arabic
            reciterName.text = reciter.name_ar
            chapterImageCard.setCardBackgroundColor(
                    if (chapter.id % 2 == 0) Color.parseColor("#336e6a") else Color.parseColor(
                            "#dd5f56"
                    )
            )
            chapterImage.setImageDrawable(
                    AppCompatResources.getDrawable(
                            binding.root.context, drawableId
                    )
            )
            chapterSeek.trackActiveTintList = ColorStateList.valueOf(dominantColor)
            // chapterSeek.thumbTintList = ColorStateList.valueOf(dominantColor)
            mediaService.run {
                chapterPlayPause.icon = if (isMediaPlaying) AppCompatResources.getDrawable(
                        binding.root.context, com.hifnawy.quran.shared.R.drawable.media_pause_black
                ) else AppCompatResources.getDrawable(
                        binding.root.context, com.hifnawy.quran.shared.R.drawable.media_play_black
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

        return "$secondsString : $minutesString${if (hours > 0) " : $hoursString" else if (showHours) " : ۰۰" else ""}"
    }

    private inner class MediaUpdatesReceiver : BroadcastReceiver() {

        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)) {
                reciter = intent.getTypedSerializable(Constants.IntentDataKeys.RECITER.name)!!
                chapter = intent.getTypedSerializable(Constants.IntentDataKeys.CHAPTER.name)!!
                val durationMs = intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_DURATION.name, -1L)
                val currentPosition =
                    intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, -1L)

                updateUI(reciter, chapter)

                if ((durationMs != -1L) and (currentPosition != -1L)) with(binding) {
                    chapterDuration.text = "${
                        getDuration(
                                currentPosition, (Duration.ofMillis(durationMs).toHours() > 0)
                        )
                    } \\ ${
                        getDuration(
                                durationMs,
                                (Duration.ofMillis(durationMs).toHours() > 0)
                        )
                    }"

                    if ((0 <= currentPosition) and (currentPosition <= durationMs) and !chapterSeek.isFocused) {
                        chapterSeek.valueFrom = 0f
                        chapterSeek.valueTo = durationMs.toFloat()
                        chapterSeek.value = currentPosition.toFloat()
                    }
                }
            } else {
                // do nothing
            }
        }
    }
}