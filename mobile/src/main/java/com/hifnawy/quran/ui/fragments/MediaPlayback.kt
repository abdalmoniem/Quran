package com.hifnawy.quran.ui.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.math.MathUtils.lerp
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.DownloadDialogBinding
import com.hifnawy.quran.databinding.FragmentMediaPlaybackBinding
import com.hifnawy.quran.shared.extensions.NumberExt.dp
import com.hifnawy.quran.shared.extensions.NumberExt.hours
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.managers.DownloadWorkManager
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.ui.activities.MainActivity
import com.hifnawy.quran.ui.activities.MainActivityDirections
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import com.hifnawy.quran.shared.R as sharedR
import com.hoko.blur.HokoBlur as Blur

private val TAG = MediaPlayback::class.simpleName

/**
 * A simple [Fragment] subclass.
 */
class MediaPlayback : Fragment() {

    private val mediaUpdatesReceiver = MediaUpdatesReceiver()
    private var chapterPosition: Long = 0L
    private val parentActivity: MainActivity by lazy { (activity as MainActivity) }
    private val downloadRequestID by lazy { UUID.fromString(getString(sharedR.string.SINGLE_DOWNLOAD_WORK_REQUEST_ID)) }
    private val pauseDrawable by lazy {
        AppCompatResources.getDrawable(binding.root.context, sharedR.drawable.media_pause_black)
    }
    private val playDrawable by lazy {
        AppCompatResources.getDrawable(binding.root.context, sharedR.drawable.media_play_black)
    }
    private lateinit var reciter: Reciter
    private lateinit var chapter: Chapter
    private lateinit var currentChapter: Chapter
    private lateinit var binding: FragmentMediaPlaybackBinding
    private lateinit var sharedPrefsManager: SharedPreferencesManager
    private var chapterDurationMs: Long = 0L
    private var appBarHeight: Int = 0
    private var isChapterSeekTouched = false
    private var waveFormLoaded = false
    private var fileBytesFetcherJob: Job? = null

    @SuppressLint(
            "DiscouragedApi", "SetTextI18n", "ClickableViewAccessibility",
            "UnspecifiedRegisterReceiverFlag"
    )
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentMediaPlaybackBinding.inflate(layoutInflater, container, false)
        sharedPrefsManager = SharedPreferencesManager(binding.root.context)

        reciter = MediaPlaybackArgs.fromBundle(requireArguments()).reciter
        chapter = MediaPlaybackArgs.fromBundle(requireArguments()).chapter
        chapterPosition = MediaPlaybackArgs.fromBundle(requireArguments()).chapterPosition

        with(parentActivity.binding) {
            appBar.viewTreeObserver.addOnGlobalLayoutListener(object :
                                                                      ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    appBar.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    appBarHeight = appBar.height
                    val appBarLayoutParams = appBar.layoutParams as ConstraintLayout.LayoutParams
                    appBarLayoutParams.height = 1
                    appBar.layoutParams = appBarLayoutParams
                }
            })
        }

        with(binding) {
            chapterPlayPause.setOnClickListener {
                parentActivity.startForegroundService(Intent(
                        binding.root.context, MediaService::class.java
                ).apply {
                    action = Constants.MediaServiceActions.TOGGLE_MEDIA.name
                })
            }

            chapterNext.setOnClickListener {
                waveFormLoaded = false
                parentActivity.startForegroundService(Intent(
                        binding.root.context, MediaService::class.java
                ).apply {
                    action = Constants.MediaServiceActions.SKIP_TO_NEXT_MEDIA.name
                })
            }

            chapterPrevious.setOnClickListener {
                waveFormLoaded = false
                parentActivity.startForegroundService(Intent(
                        binding.root.context, MediaService::class.java
                ).apply {
                    action = Constants.MediaServiceActions.SKIP_TO_PREVIOUS_MEDIA.name
                })
            }

            chapterSeek.onProgressChanged = { progress, fromUser ->
                if (fromUser) {
                    val showHours = chapterDurationMs.hours > 0

                    chapterDuration.text =
                            "${getDuration(progress.toValue(chapterDurationMs), showHours)} \\ " +
                            getDuration(chapterDurationMs, showHours)
                }
            }

            chapterSeek.onStartTracking = { _ ->
                isChapterSeekTouched = true
            }

            chapterSeek.onStopTracking = { progress ->
                isChapterSeekTouched = false

                parentActivity.startForegroundService(Intent(
                        binding.root.context,
                        MediaService::class.java
                ).apply {
                    action = Constants.MediaServiceActions.SEEK_MEDIA.name

                    putExtra(
                            Constants.IntentDataKeys.CHAPTER_POSITION.name,
                            progress.toValue(chapterDurationMs)
                    )
                })
            }
            // chapterSeek.setOnTouchListener { _, motionEvent ->
            //     when (motionEvent.action) {
            //         MotionEvent.ACTION_UP   -> {
            //             isChapterSeekTouched = false
            //
            //             parentActivity.startForegroundService(Intent(
            //                     binding.root.context,
            //                     MediaService::class.java
            //             ).apply {
            //                 action = Constants.MediaServiceActions.SEEK_MEDIA.name
            //
            //                 putExtra(
            //                         Constants.IntentDataKeys.CHAPTER_POSITION.name,
            //                         chapterSeek.progress.fromPercentage(chapterDurationMs)
            //                 )
            //             })
            //
            //             return@setOnTouchListener true
            //         }
            //
            //         MotionEvent.ACTION_DOWN -> {
            //             isChapterSeekTouched = true
            //
            //             return@setOnTouchListener true
            //         }
            //
            //         else                    -> return@setOnTouchListener false
            //     }
            // }
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
                            if (binding.root.currentState == R.id.maximized) {
                                binding.root.transitionToState(R.id.minimized)
                            } else {
                                parentActivity.onSupportNavigateUp()
                            }
                        }
                    })
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            parentActivity.registerReceiver(
                    mediaUpdatesReceiver,
                    IntentFilter(getString(sharedR.string.quran_media_service_updates)).apply {
                        addCategory(Constants.ServiceUpdates.MEDIA_PLAYBACK_UPDATES.name)
                    },
                    Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            parentActivity.registerReceiver(mediaUpdatesReceiver,
                                            IntentFilter(getString(sharedR.string.quran_media_service_updates)).apply {
                                                addCategory(Constants.ServiceUpdates.MEDIA_PLAYBACK_UPDATES.name)
                                            })
        }

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
            parentActivity.startForegroundService(Intent(
                    context, MediaService::class.java
            ).apply {
                action = Constants.MediaServiceActions.CANCEL_DOWNLOADS.name
            })
            dialog.dismiss()
        }
        /**
         * TODO:
         * this is in correct, this line adds a new observer everytime it's being called
         * which means there'll be duplicate actions being taken in the same time while the data
         * its parsing is changing, check:
         * [com.hifnawy.quran.shared.managers.MediaManager.observeSingleDownloadProgress] for details
         * on how to fix
         */
        workManager.getWorkInfoByIdLiveData(downloadRequestID)
            .observe(viewLifecycleOwner) { workInfo ->
                observeWorker(workInfo, dialog, dialogBinding)
            }

        parentActivity.startForegroundService(Intent(
                binding.root.context, MediaService::class.java
        ).apply {
            action = Constants.MediaServiceActions.PLAY_MEDIA.name

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
        if (!::currentChapter.isInitialized || currentChapter != chapter) {
            waveFormLoaded = false
            currentChapter = chapter
        }

        if (!waveFormLoaded && fileBytesFetcherJob == null) {
            fileBytesFetcherJob =
                    lifecycleScope.launch(context = Dispatchers.IO, start = CoroutineStart.LAZY) {
                        fileBytesFetcherJob = null
                        val chapterFile =
                                Constants.getChapterFile(binding.root.context, reciter, chapter)
                        if (chapterFile.exists()) {
                            binding.chapterSeek.setRawData(chapterFile.toBytes())
                            waveFormLoaded = true
                        }
                    }

            fileBytesFetcherJob?.start()
        }
        @SuppressLint("DiscouragedApi")
        val drawableId = resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}",
                "drawable",
                binding.root.context.packageName
        )
        val drawable = AppCompatResources.getDrawable(binding.root.context, drawableId)
        val drawableBitmap = (drawable as BitmapDrawable).bitmap
        val bitmap = Blur.with(context)
            .scheme(Blur.SCHEME_NATIVE) // different implementation, RenderScript、OpenGL、Native(default) and Java
            .mode(Blur.MODE_GAUSSIAN) // blur algorithms，Gaussian、Stack(default) and Box
            .radius(3) // blur radius，max=25，default=5
            .sampleFactor(2.0f)
            .processor()
            .blur(drawableBitmap)
        val dominantColor = Palette.from(drawableBitmap).generate().getDominantColor(Color.RED)

        with(binding) {
            chapterBackgroundImage.setImageDrawable(bitmap.toDrawable(resources))

            chapterName.text = chapter.nameArabic
            reciterName.text = reciter.nameArabic

            chapterImageCard.setCardBackgroundColor(
                    if (chapter.id % 2 == 0) Color.parseColor("#336e6a")
                    else Color.parseColor("#dd5f56")
            )
            chapterImage.setImageDrawable(drawable)
            // chapterSeek.trackActiveTintList = ColorStateList.valueOf(dominantColor)
            // chapterSeek.valueFrom = 0f
            // chapterSeek.valueTo = chapterDuration.toFloat()
            // chapterSeek.waveColor = dominantColor
            if (!isChapterSeekTouched) chapterSeek.progress =
                    chapterPosition.toPercentage(chapterDuration)
            chapterPlayPause.icon =
                    if (isMediaPlaying) pauseDrawable
                    else playDrawable
            chapterPlayPause.setBackgroundColor(dominantColor)

            chapterNext.setBackgroundColor(dominantColor)
            chapterPrevious.setBackgroundColor(dominantColor)
        }
    }

    private var lastWorkInfoId: UUID = UUID.randomUUID()

    @SuppressLint("SetTextI18n")
    private fun observeWorker(
            workInfo: WorkInfo, dialog: AlertDialog, dialogBinding: DownloadDialogBinding
    ) {
        val decimalFormat = DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))

        if ((workInfo.state != WorkInfo.State.RUNNING) &&
            (workInfo.state != WorkInfo.State.SUCCEEDED) &&
            (workInfo.state != WorkInfo.State.FAILED)
        ) return
        val dataSource =
                if ((workInfo.state == WorkInfo.State.SUCCEEDED) ||
                    (workInfo.state == WorkInfo.State.FAILED)
                ) workInfo.outputData
                else workInfo.progress

        if (dataSource.keyValueMap.isEmpty()) return

        Log.d(TAG, "${workInfo.state}\n$dataSource")

        if (workInfo.state == WorkInfo.State.FAILED) {
            dialog.dismiss()

            if (workInfo.id == lastWorkInfoId) {
                DialogBuilder.showErrorDialog(
                        this@MediaPlayback.binding.root.context,
                        getString(R.string.connection_error_title),
                        getString(R.string.connection_error_message),
                        getString(R.string.connection_error_action)
                ) { _, _ ->
                    parentActivity.navController.navigate(MainActivityDirections.toRecitersList())
                }

                binding.root.transitionToEnd()
            }
            return
        }
        val downloadStatus = DownloadWorkManager.DownloadStatus.valueOf(
                dataSource.getString(DownloadWorkManager.DownloadWorkerInfo.DOWNLOAD_STATUS.name)
                ?: return
        )
        val bytesDownloaded = dataSource.getLong(
                DownloadWorkManager.DownloadWorkerInfo.BYTES_DOWNLOADED.name, -1L
        )
        val fileSize = dataSource.getInt(
                DownloadWorkManager.DownloadWorkerInfo.FILE_SIZE.name, -1
        )
        val progress = dataSource.getFloat(
                DownloadWorkManager.DownloadWorkerInfo.PROGRESS.name, -1f
        )

        with(dialogBinding) {
            when (downloadStatus) {
                DownloadWorkManager.DownloadStatus.STARTING_DOWNLOAD -> {
                    lastWorkInfoId = workInfo.id
                    dialog.show()
                    downloadDialogChapterProgress.min = 0
                    downloadDialogChapterProgress.max = 100
                    downloadDialogChapterProgress.progress = 0
                    downloadDialogChapterDownloadMessage.text = "${
                        context?.getString(
                                sharedR.string.loading_chapter,
                                chapter.nameArabic
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
                                sharedR.string.loading_chapter, chapter.nameArabic
                        )
                    }\n${decimalFormat.format(bytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                        decimalFormat.format(
                                fileSize.toFloat() / (1024 * 1024)
                        )
                    } مب. (${decimalFormat.format(progress)}٪)"
                }

                DownloadWorkManager.DownloadStatus.FILE_EXISTS,
                DownloadWorkManager.DownloadStatus.FINISHED_DOWNLOAD -> dialog.dismiss()

                DownloadWorkManager.DownloadStatus.DOWNLOAD_ERROR,
                DownloadWorkManager.DownloadStatus.DOWNLOAD_INTERRUPTED,
                DownloadWorkManager.DownloadStatus.CONNECTION_FAILURE -> Unit
            }
        }
    }

    private fun getDuration(durationMs: Long, showHours: Boolean): String {
        val duration = durationMs.milliseconds
        val durationS = duration.inWholeSeconds
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
            if (!intent.hasCategory(Constants.ServiceUpdates.MEDIA_PLAYBACK_UPDATES.name)) return

            reciter = intent.getTypedSerializable(Constants.IntentDataKeys.RECITER.name) ?: return
            chapter = intent.getTypedSerializable(Constants.IntentDataKeys.CHAPTER.name) ?: return
            val isMediaPlaying =
                    intent.getBooleanExtra(Constants.IntentDataKeys.IS_MEDIA_PLAYING.name, false)
            chapterDurationMs = intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_DURATION.name, -1L)
            val currentPosition =
                    intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, -1L)

            updateUI(reciter, chapter, isMediaPlaying = isMediaPlaying)

            if ((chapterDurationMs == -1L) || (currentPosition == -1L)) return
            if ((0 > currentPosition) || (currentPosition > chapterDurationMs) || binding.chapterSeek.isFocused) return

            with(binding) {
                // chapterSeek.valueFrom = 0f
                // chapterSeek.valueTo = durationMs.toFloat()
                if (!isChapterSeekTouched) {
                    chapterDuration.text = "${
                        getDuration(
                                currentPosition, (Duration.ofMillis(chapterDurationMs).toHours() > 0)
                        )
                    } \\ ${
                        getDuration(
                                chapterDurationMs, (Duration.ofMillis(chapterDurationMs).toHours() > 0)
                        )
                    }"

                    chapterSeek.progress = currentPosition.toPercentage(chapterDurationMs)
                    // chapterSeek.value = currentPosition.toFloat()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class MotionLayoutTransitionListener : MotionLayout.TransitionListener {

        val chapterPreviousIconSize = binding.chapterPrevious.iconSize.toFloat()
        val chapterPlayPauseIconSize = binding.chapterPlayPause.iconSize.toFloat()
        val chapterNextIconSize = binding.chapterNext.iconSize.toFloat()
        val minimizedMediaControlsIconSize = 80f.dp

        override fun onTransitionStarted(motionLayout: MotionLayout?, startId: Int, endId: Int) = Unit

        override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
        ) {
            val minimizing = (startId == R.id.maximized) && (endId == R.id.minimized)
            with(parentActivity.binding) {
                if (!appBar.isInLayout) {
                    appBar.updateLayoutParams {
                        height =
                                (if (minimizing) lerp(1f.dp, appBarHeight.toFloat(), progress)
                                else lerp(appBarHeight.toFloat(), 1f.dp, progress)).toInt()
                    }
                }

                if (!fragmentContainer.isInLayout) {
                    fragmentContainer.updateLayoutParams<FrameLayout.LayoutParams> {
                        bottomMargin = (if (minimizing) lerp(
                                resources.getDimension(R.dimen.media_player_minimized_height),
                                0f.dp,
                                progress
                        ) else lerp(
                                0f.dp,
                                resources.getDimension(R.dimen.media_player_minimized_height),
                                progress
                        )).toInt()
                    }
                }
            }

            with(binding) {
                chapterSeek.isTouchable = !minimizing
                chapterName.setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        if (minimizing) lerp(80f, 25f, progress)
                        else lerp(25f, 80f, progress)
                )

                reciterName.setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        if (minimizing) lerp(25f, 15f, progress)
                        else lerp(15f, 25f, progress)
                )
                chapterPrevious.iconSize =
                        (if (minimizing) lerp(
                                chapterPreviousIconSize,
                                minimizedMediaControlsIconSize,
                                progress
                        )
                        else lerp(
                                minimizedMediaControlsIconSize,
                                chapterPreviousIconSize,
                                progress
                        )).toInt()

                chapterPrevious.background.alpha =
                        (if (minimizing) lerp(255f, 0f, progress)
                        else lerp(0f, 255f, progress)).toInt()

                chapterPlayPause.iconSize =
                        (if (minimizing) lerp(
                                chapterPlayPauseIconSize,
                                minimizedMediaControlsIconSize,
                                progress
                        ) else lerp(
                                minimizedMediaControlsIconSize,
                                chapterPlayPauseIconSize,
                                progress
                        )).toInt()
                chapterPlayPause.background.alpha =
                        (if (minimizing) lerp(255f, 0f, progress)
                        else lerp(0f, 255f, progress)).toInt()

                chapterNext.iconSize =
                        (if (minimizing) lerp(
                                chapterNextIconSize,
                                minimizedMediaControlsIconSize,
                                progress
                        )
                        else lerp(minimizedMediaControlsIconSize, chapterNextIconSize, progress)).toInt()
                chapterNext.background.alpha =
                        (if (minimizing) lerp(255f, 0f, progress)
                        else lerp(0f, 255f, progress)).toInt()
            }
        }

        override fun onTransitionCompleted(motionLayout: MotionLayout?, currentState: Int) {
            val minimized = currentState == R.id.minimized
            binding.root.isInteractionEnabled = false

            with(parentActivity.binding) {
                if (!appBar.isInLayout) {
                    appBar.updateLayoutParams { height = if (minimized) appBarHeight else 1.dp }
                }

                if (!fragmentContainer.isInLayout) {
                    fragmentContainer.updateLayoutParams<FrameLayout.LayoutParams> {
                        bottomMargin =
                                if (minimized) resources.getDimension(R.dimen.media_player_minimized_height)
                                    .toInt() else 0.dp
                    }
                }
            }

            with(binding) {
                chapterPrevious.background.alpha = if (minimized) 0 else 255
                chapterPlayPause.background.alpha = if (minimized) 0 else 255
                chapterNext.background.alpha = if (minimized) 0 else 255
            }
        }

        override fun onTransitionTrigger(
                motionLayout: MotionLayout?,
                triggerId: Int,
                positive: Boolean,
                progress: Float
        ) = Unit
    }

    private fun File.toBytes(): ByteArray {
        val file = this
        val fileIO = RandomAccessFile(file, "r")
        val byteArray = ByteArray(fileIO.length().toInt())

        fileIO.readFully(byteArray)
        fileIO.close()

        return byteArray
    }

    private infix fun Long.toPercentage(max: Long): Float = (this.toFloat() / max) * 100f
    private infix fun Float.toValue(max: Long): Long = ((this * max) / 100f).toLong()
}
