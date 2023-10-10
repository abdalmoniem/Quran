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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.DownloadDialogBinding
import com.hifnawy.quran.databinding.FragmentMediaPlaybackBinding
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.managers.DownloadWorkManager
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.ui.activities.MainActivity
import com.hifnawy.quran.ui.dialogs.DialogBuilder
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

    @Suppress("PrivatePropertyName")
    private val TAG = MediaPlayback::class.simpleName
    private val mediaUpdatesReceiver = MediaUpdatesReceiver()
    private lateinit var reciter: Reciter
    private lateinit var chapter: Chapter
    private var chapterPosition: Long = 0L
    private val parentActivity: MainActivity by lazy { (activity as MainActivity) }
    private lateinit var binding: FragmentMediaPlaybackBinding
    private lateinit var sharedPrefsManager: SharedPreferencesManager
    private var appBarHeight: Int = 0

    @SuppressLint("DiscouragedApi", "SetTextI18n", "ClickableViewAccessibility")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentMediaPlaybackBinding.inflate(layoutInflater, container, false)
        sharedPrefsManager = SharedPreferencesManager(binding.root.context)

        reciter = MediaPlaybackArgs.fromBundle(requireArguments()).reciter
        chapter = MediaPlaybackArgs.fromBundle(requireArguments()).chapter
        chapterPosition = MediaPlaybackArgs.fromBundle(requireArguments()).chapterPosition

        with(parentActivity.binding) {
            appBarHeight = appBar.height
            val appBarLayoutParams = appBar.layoutParams as ConstraintLayout.LayoutParams
            appBarLayoutParams.height = 1
            appBar.layoutParams = appBarLayoutParams
        }

        with(binding) {
            chapterPlayPause.setOnClickListener {
                parentActivity.startForegroundService(Intent(
                        binding.root.context, MediaService::class.java
                ).apply {
                    action = Constants.Actions.TOGGLE_MEDIA.name
                })
            }

            chapterNext.setOnClickListener {
                parentActivity.startForegroundService(Intent(
                        binding.root.context, MediaService::class.java
                ).apply {
                    action = Constants.Actions.SKIP_TO_NEXT_MEDIA.name
                })
            }

            chapterPrevious.setOnClickListener {
                parentActivity.startForegroundService(Intent(
                        binding.root.context, MediaService::class.java
                ).apply {
                    action = Constants.Actions.SKIP_TO_PREVIOUS_MEDIA.name
                })
            }

            chapterSeek.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val showHours = Duration.ofMillis(chapterSeek.valueTo.toLong()).toHours() > 0

                    chapterDuration.text = "${
                        getDuration(value.toLong(), showHours)
                    } \\ ${
                        getDuration(chapterSeek.valueTo.toLong(), showHours)
                    }"

                    parentActivity.startForegroundService(Intent(
                            binding.root.context,
                            MediaService::class.java
                    ).apply {
                        action = Constants.Actions.SEEK_MEDIA.name

                        putExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, value.toLong())
                    })
                }
            }

            chapterBackgroundImageContainer.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_DOWN) root.isInteractionEnabled = true
                false
            }

            root.isInteractionEnabled = false
            root.setTransitionListener(MotionLayoutTransitionListener())
            root.transitionToStart()

            parentActivity.onBackPressedDispatcher.addCallback(
                    viewLifecycleOwner,
                    object : OnBackPressedCallback(true) {
                        override
                        fun handleOnBackPressed() {
                            Log.d(
                                    TAG,
                                    "maxID: ${R.id.maximized}, minID: ${R.id.minimized}, current: ${binding.root.currentState}"
                            )
                            if (binding.root.currentState == R.id.maximized) {
                                binding.root.transitionToState(R.id.minimized)
                                // binding.root.jumpToState(R.id.minimized)
                                // binding.root.updateState()
                            } else {
                                parentActivity.onSupportNavigateUp()
                            }
                        }
                    })
        }

        parentActivity.registerReceiver(mediaUpdatesReceiver,
                                        IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_updates)).apply {
                                            addCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)
                                        })

        playChapter(
                chapter,
                if ((reciter.id == sharedPrefsManager.lastReciter?.id) && (chapter.id == sharedPrefsManager.lastChapter?.id)) sharedPrefsManager.lastChapterPosition
                else chapterPosition,
                if ((reciter.id == sharedPrefsManager.lastReciter?.id) && (chapter.id == sharedPrefsManager.lastChapter?.id)) sharedPrefsManager.lastChapterDuration
                else 100L
        )

        return binding.root
    }

    override fun onDestroy() {
        with(parentActivity) {
            try {
                unregisterReceiver(mediaUpdatesReceiver)
            } catch (_: IllegalArgumentException) {
                Log.w(
                        TAG,
                        "Could not unregister ${::mediaUpdatesReceiver.name}, it was probably unregistered in an earlier stage!!!"
                )
            }
        }

        super.onDestroy()
    }

    private fun playChapter(
            chapter: Chapter,
            startPosition: Long = chapterPosition,
            duration: Long = 100L
    ) {
        updateUI(reciter, chapter, chapterPosition, duration)
        val workManager = WorkManager.getInstance(binding.root.context)
        val (dialog, dialogBinding) = DialogBuilder.prepareDownloadDialog(
                binding.root.context, DialogBuilder.DownloadType.SINGLE
        )

        dialogBinding.downloadDialogCancelDownload.setOnClickListener {
            workManager.cancelUniqueWork(getString(com.hifnawy.quran.shared.R.string.downloadWorkManagerUniqueWorkName))
            dialog.dismiss()
        }

        workManager.getWorkInfosByTagLiveData(getString(com.hifnawy.quran.shared.R.string.downloadWorkManagerUniqueWorkName))
            .observe(viewLifecycleOwner) { workInfos ->
                observeWorker(workInfos, dialog, dialogBinding)
            }

        parentActivity.startForegroundService(Intent(
                binding.root.context, MediaService::class.java
        ).apply {
            action = Constants.Actions.PLAY_MEDIA.name

            putExtra(Constants.IntentDataKeys.RECITER.name, reciter)
            putExtra(Constants.IntentDataKeys.CHAPTER.name, chapter)
            putExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, startPosition)
        })
    }

    private fun updateUI(
            reciter: Reciter,
            chapter: Chapter,
            chapterPosition: Long = 0L,
            chapterDuration: Long = 100L,
            isMediaPlaying: Boolean = false
    ) {
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
            chapterSeek.valueFrom = 0f
            chapterSeek.valueTo = chapterDuration.toFloat()
            chapterSeek.value = chapterPosition.toFloat()
            chapterPlayPause.icon = if (isMediaPlaying) AppCompatResources.getDrawable(
                    binding.root.context, com.hifnawy.quran.shared.R.drawable.media_pause_black
            ) else AppCompatResources.getDrawable(
                    binding.root.context, com.hifnawy.quran.shared.R.drawable.media_play_black
            )

            chapterPlayPause.setBackgroundColor(dominantColor)
            chapterNext.setBackgroundColor(dominantColor)
            chapterPrevious.setBackgroundColor(dominantColor)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeWorker(
            workInfos: MutableList<WorkInfo>?, dialog: AlertDialog, dialogBinding: DownloadDialogBinding
    ) {
        val decimalFormat = DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))

        if (workInfos == null) return
        val workInfo = workInfos.find { workInfo ->
            workInfo.tags.find { tag -> tag == getString(com.hifnawy.quran.shared.R.string.downloadWorkManagerUniqueWorkName) }
                ?.let { true } ?: false
        } ?: return

        Log.d(TAG, "${workInfo.state} - ${workInfo.progress}")

        if (workInfo.state == WorkInfo.State.FAILED) return
        if (workInfo.state == WorkInfo.State.SUCCEEDED) return
        val downloadStatus = DownloadWorkManager.DownloadStatus.valueOf(
                workInfo.progress.getString(DownloadWorkManager.DownloadWorkerInfo.DOWNLOAD_STATUS.name)
                    ?: return
        )
        val bytesDownloaded = workInfo.progress.getLong(
                DownloadWorkManager.DownloadWorkerInfo.BYTES_DOWNLOADED.name, -1L
        )
        val fileSize = workInfo.progress.getInt(
                DownloadWorkManager.DownloadWorkerInfo.FILE_SIZE.name, -1
        )
        val progress = workInfo.progress.getFloat(
                DownloadWorkManager.DownloadWorkerInfo.PROGRESS.name, -1f
        )

        with(dialogBinding) {
            when (downloadStatus) {
                DownloadWorkManager.DownloadStatus.STARTING_DOWNLOAD -> {
                    dialog.show()
                    downloadDialogChapterProgress.min = 0
                    downloadDialogChapterProgress.max = 100
                    downloadDialogChapterProgress.progress = 0
                    downloadDialogChapterDownloadMessage.text = "${
                        context?.getString(
                                com.hifnawy.quran.shared.R.string.loading_chapter,
                                chapter.name_arabic
                        )
                    }\n${decimalFormat.format(0)} مب. \\ ${decimalFormat.format(0)} مب. (${
                        decimalFormat.format(
                                0
                        )
                    }٪)"
                }

                DownloadWorkManager.DownloadStatus.DOWNLOADING -> {
                    downloadDialogChapterProgress.progress = progress.toInt()
                    downloadDialogChapterDownloadMessage.text = "${
                        context?.getString(
                                com.hifnawy.quran.shared.R.string.loading_chapter, chapter.name_arabic
                        )
                    }\n${decimalFormat.format(bytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                        decimalFormat.format(
                                fileSize.toFloat() / (1024 * 1024)
                        )
                    } مب. (${decimalFormat.format(progress)}٪)"

                }

                DownloadWorkManager.DownloadStatus.FILE_EXISTS,
                DownloadWorkManager.DownloadStatus.FINISHED_DOWNLOAD -> dialog.dismiss()

                DownloadWorkManager.DownloadStatus.DOWNLOAD_ERROR -> {
                    dialog.dismiss()

                    DialogBuilder.showErrorDialog(
                            this@MediaPlayback.binding.root.context,
                            getString(R.string.connection_error_title),
                            getString(R.string.connection_error_message),
                            getString(R.string.connection_error_action)
                    ) { _, _ ->
                        parentFragmentManager.beginTransaction().replace(
                                parentActivity.binding.fragmentContainer.id, RecitersList()
                        ).commit()
                    }
                }

                DownloadWorkManager.DownloadStatus.DOWNLOAD_INTERRUPTED -> Unit
            }
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
            if (!intent.hasCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)) return

            reciter = intent.getTypedSerializable(Constants.IntentDataKeys.RECITER.name) ?: return
            chapter = intent.getTypedSerializable(Constants.IntentDataKeys.CHAPTER.name) ?: return
            val isMediaPlaying =
                intent.getBooleanExtra(Constants.IntentDataKeys.IS_MEDIA_PLAYING.name, false)
            val durationMs = intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_DURATION.name, -1L)
            val currentPosition =
                intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, -1L)

            updateUI(reciter, chapter, isMediaPlaying = isMediaPlaying)

            if ((durationMs == -1L) || (currentPosition == -1L)) return
            if ((0 > currentPosition) || (currentPosition > durationMs) || binding.chapterSeek.isFocused) return

            with(binding) {
                chapterDuration.text = "${
                    getDuration(
                            currentPosition, (Duration.ofMillis(durationMs).toHours() > 0)
                    )
                } \\ ${
                    getDuration(
                            durationMs, (Duration.ofMillis(durationMs).toHours() > 0)
                    )
                }"

                chapterSeek.valueFrom = 0f
                chapterSeek.valueTo = durationMs.toFloat()
                chapterSeek.value = currentPosition.toFloat()
            }
        }
    }

    private inner class MotionLayoutTransitionListener : MotionLayout.TransitionListener {

        val fragmentContainerLayoutParams =
            parentActivity.binding.fragmentContainer.layoutParams as FrameLayout.LayoutParams
        val appBarLayoutParams =
            parentActivity.binding.appBar.layoutParams as ConstraintLayout.LayoutParams

        override fun onTransitionStarted(motionLayout: MotionLayout?, startId: Int, endId: Int) {

        }

        override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
        ) {
            val minimizing = (startId == R.id.maximized) && (endId == R.id.minimized)
            fragmentContainerLayoutParams.bottomMargin = if (minimizing) {
                lerp(
                        resources.getDimension(R.dimen.media_player_minimized_height).toInt(),
                        0.dp,
                        progress
                ).toInt()
            } else {
                lerp(
                        0.dp,
                        resources.getDimension(R.dimen.media_player_minimized_height).toInt(),
                        progress
                ).toInt()
            }

            appBarLayoutParams.height = if (minimizing) {
                lerp(1.dp, appBarHeight, progress).toInt()
            } else {
                lerp(appBarHeight, 1.dp, progress).toInt()
            }

            parentActivity.binding.appBar.layoutParams = appBarLayoutParams
            parentActivity.binding.fragmentContainer.layoutParams = fragmentContainerLayoutParams
        }

        override fun onTransitionCompleted(motionLayout: MotionLayout?, currentState: Int) {
            binding.root.isInteractionEnabled = false

            appBarLayoutParams.height = if (currentState == R.id.minimized) {
                appBarHeight
            } else {
                1.dp
            }
            fragmentContainerLayoutParams.bottomMargin = if (currentState == R.id.minimized) {
                resources.getDimension(R.dimen.media_player_minimized_height).toInt()
            } else {
                0.dp
            }
            parentActivity.binding.appBar.layoutParams = appBarLayoutParams
        }

        override fun onTransitionTrigger(
                motionLayout: MotionLayout?,
                triggerId: Int,
                positive: Boolean,
                progress: Float
        ) {

        }

        @Suppress("SpellCheckingInspection")
        fun lerp(valueFrom: Int, valueTo: Int, delta: Float): Float =
            (valueFrom * (1f - delta)) + (valueTo * delta)

        val Int.dp: Int
            get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    }
}
