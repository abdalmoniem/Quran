package com.hifnawy.quran.ui.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hifnawy.quran.R
import com.hifnawy.quran.adapters.ChaptersListAdapter
import com.hifnawy.quran.databinding.FragmentChaptersListBinding
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.managers.DownloadWorkManager
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.ui.activities.MainActivity
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID

@Suppress("PrivatePropertyName")
private val TAG = ChaptersList::class.java.simpleName

/**
 * A simple [Fragment] subclass.
 */
class ChaptersList : Fragment() {

    private val mediaUpdatesReceiver = MediaUpdatesReceiver()
    private val parentActivity: MainActivity by lazy { (activity as MainActivity) }
    private val sharedPrefsManager by lazy { SharedPreferencesManager(binding.root.context) }
    private val reciter by lazy { ChaptersListArgs.fromBundle(requireArguments()).reciter }
    private val workManager by lazy { WorkManager.getInstance(binding.root.context) }
    private val downloadRequestID by lazy { UUID.fromString(getString(com.hifnawy.quran.shared.R.string.BULK_DOWNLOAD_WORK_REQUEST_ID)) }
    private var chapters: List<Chapter> = mutableListOf()
    private lateinit var binding: FragmentChaptersListBinding
    private lateinit var chaptersListAdapter: ChaptersListAdapter

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentChaptersListBinding.inflate(inflater, container, false)

        lifecycleScope.launch {
            chapters =
                lifecycleScope.async(context = Dispatchers.IO) { QuranAPI.getChaptersList() }.await()

            withContext(Dispatchers.Main) {
                with(binding) {
                    chaptersListAdapter = ChaptersListAdapter(
                            root.context, ArrayList(chapters)
                    ) { position, chapter, itemView ->
                        Log.d(
                                TAG,
                                "clicked on $position: ${chapter.translated_name?.name} ${itemView.verseCount.text}"
                        )
                        chapterSearch.text = null
                        chapterSearch.clearFocus()
                        val inputMethodManager =
                            requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
                        // Hide:
                        inputMethodManager.hideSoftInputFromWindow(root.windowToken, 0)

                        parentActivity.binding.mediaPlaybackFragmentContainer.visibility = View.VISIBLE
                        parentActivity.mediaPlaybackNavController.navigate(
                                MediaPlaybackDirections.toMediaPlayback(
                                        reciter,
                                        chapter
                                )
                        )
                    }

                    sharedPrefsManager.lastChapter?.let {
                        if (MediaService.isMediaPlaying) {
                            val currentChapter = chapters.find { chapter -> chapter.id == it.id }
                            currentChapter?.let {
                                chaptersListAdapter.notifyItemChanged(chapters.indexOf(currentChapter))
                            }

                            Log.d(TAG, "scrolling to: ${chapters.indexOf(currentChapter)}")

                            chaptersList.scrollToPosition(chapters.indexOf(currentChapter))
                        }
                    }

                    parentActivity.registerReceiver(mediaUpdatesReceiver,
                                                    IntentFilter(getString(com.hifnawy.quran.shared.R.string.quran_media_service_updates)).apply {
                                                        addCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)
                                                    })

                    chaptersList.layoutManager =
                        GridLayoutManager(root.context, 3, GridLayoutManager.VERTICAL, false)
                    chaptersList.adapter = chaptersListAdapter

                    chapterSearch.addTextChangedListener(onTextChanged = { charSequence, _, _, _ ->
                        if (charSequence.toString().isEmpty()) {
                            chaptersListAdapter.setChapters(chapters)
                        } else {
                            val searchResults = chapters.filter { chapter ->
                                chapter.name_arabic.contains(charSequence.toString())
                            }

                            if (searchResults.isNotEmpty()) {
                                chaptersListAdapter.setChapters(searchResults)
                            } else {
                                chaptersListAdapter.clear()
                            }
                        }
                    })

                    downloadAllChapters.setOnClickListener {
                        DownloadWorkManager.chapters = chaptersListAdapter.getChapters()
                        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorkManager>()
                            .setInputData(
                                    workDataOf(
                                            Constants.IntentDataKeys.SINGLE_DOWNLOAD_TYPE.name to false,
                                            Constants.IntentDataKeys.RECITER.name to DownloadWorkManager.fromReciter(
                                                    reciter
                                            )
                                    )
                            )
                            .setId(downloadRequestID)
                            .build()

                        workManager.enqueueUniqueWork(
                                getString(com.hifnawy.quran.shared.R.string.bulkDownloadWorkManagerUniqueWorkName),
                                ExistingWorkPolicy.REPLACE,
                                downloadWorkRequest
                        )
                    }
                }

                observeWorker(downloadRequestID)
            }
        }

        return binding.root
    }

    override fun onResume() {
        parentActivity.supportActionBar?.apply {
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"
            // providing subtitle for the ActionBar
            subtitle =
                "   ${getString(R.string.chapters)}: ${reciter.name_ar} ${if (reciter.style?.style != null) "(${reciter.style?.style})" else ""}"

            show()
        }

        super.onResume()
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

    @SuppressLint("SetTextI18n")
    private fun observeWorker(requestID: UUID) {
        val context = binding.root.context

        val (dialog, dialogBinding) = DialogBuilder.prepareDownloadDialog(
                binding.root.context,
                DialogBuilder.DownloadType.BULK
        )

        with(dialogBinding) {
            dialogBinding.downloadDialogCancelDownload.setOnClickListener {
                workManager.cancelWorkById(downloadRequestID)
                dialog.dismiss()
            }

            downloadDialogChapterProgress.min = 0
            downloadDialogChapterProgress.max = 100
            downloadDialogChapterProgress.progress = 0
            downloadDialogAllChaptersProgress.min = 0
            downloadDialogAllChaptersProgress.max = 100
            downloadDialogAllChaptersProgress.progress = 0
            val decimalFormat =
                DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))

            downloadDialogAllChaptersProgress.progress = 0
            downloadDialogAllChaptersDownloadMessage.text =
                context.getString(
                        com.hifnawy.quran.shared.R.string.loading_all_chapters,
                        decimalFormat.format(0)
                )

            downloadDialogChapterProgress.progress = 0
            downloadDialogChapterDownloadMessage.text = "${
                context.getString(
                        com.hifnawy.quran.shared.R.string.loading_chapter,
                        ""
                )
            }\n${decimalFormat.format(0)} مب. \\ ${
                decimalFormat.format(0)
            } مب. (${decimalFormat.format(0)}٪)"

            downloadDialogAllChaptersProgress.progress = 0
            downloadDialogAllChaptersDownloadMessage.text =
                context.getString(
                        com.hifnawy.quran.shared.R.string.loading_all_chapters,
                        decimalFormat.format(0)
                )
        }

        workManager.getWorkInfoByIdLiveData(requestID)
            .observe(viewLifecycleOwner) { workInfo ->
                if (workInfo == null) return@observe
                if ((workInfo.state != WorkInfo.State.RUNNING) &&
                    (workInfo.state != WorkInfo.State.SUCCEEDED) &&
                    (workInfo.state != WorkInfo.State.FAILED)
                ) return@observe
                val decimalFormat =
                    DecimalFormat(
                            "#.#",
                            DecimalFormatSymbols.getInstance(Locale("ar", "EG"))
                    )
                val dataSource =
                    if ((workInfo.state == WorkInfo.State.SUCCEEDED) ||
                        (workInfo.state == WorkInfo.State.FAILED)
                    ) workInfo.outputData
                    else workInfo.progress

                if (workInfo.state == WorkInfo.State.FAILED) {
                    val downloadedChapterCount = dataSource.getInt(
                            DownloadWorkManager.DownloadWorkerInfo.DOWNLOADED_CHAPTER_COUNT.name,
                            0
                    )
                    DialogBuilder.showErrorDialog(
                            binding.root.context, getString(R.string.connection_error_title),
                            getString(
                                    R.string.downloading_chapters_error_message,
                                    decimalFormat.format(context.resources.getInteger(com.hifnawy.quran.shared.R.integer.quran_chapter_count) - downloadedChapterCount)
                            ), "تمام"
                    )
                    dialog.dismiss()
                    return@observe
                }
                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                    Log.d(
                            TAG,
                            "SUCEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEED"
                    )
                    dialog.dismiss()
                    return@observe
                }

                Log.d(TAG, "${workInfo.state} - $dataSource")
                val currentChapterJSON =
                    dataSource.getString(DownloadWorkManager.DownloadWorkerInfo.DOWNLOAD_CHAPTER.name)
                val currentChapter = DownloadWorkManager.toChapter(currentChapterJSON)
                val downloadStatus = DownloadWorkManager.DownloadStatus.valueOf(
                        dataSource.getString(DownloadWorkManager.DownloadWorkerInfo.DOWNLOAD_STATUS.name)
                            ?: return@observe
                )
                val bytesDownloaded = dataSource.getLong(
                        DownloadWorkManager.DownloadWorkerInfo.BYTES_DOWNLOADED.name,
                        -1L
                )
                val fileSize = dataSource.getInt(
                        DownloadWorkManager.DownloadWorkerInfo.FILE_SIZE.name,
                        -1
                )
                val progress = dataSource.getFloat(
                        DownloadWorkManager.DownloadWorkerInfo.PROGRESS.name,
                        -1f
                )
                val currentChapterIndex = chaptersListAdapter.getChapters()
                    .indexOf(chaptersListAdapter.getChapters().find { chapter ->
                        chapter.id == currentChapter.id
                    }) + 1

                with(dialogBinding) {
                    when (downloadStatus) {
                        DownloadWorkManager.DownloadStatus.STARTING_DOWNLOAD -> {
                            dialog.show()

                            downloadDialogChapterProgress.progress = progress.toInt()
                            downloadDialogChapterDownloadMessage.setTextColor(Color.WHITE)
                            downloadDialogChapterDownloadMessage.text = "${
                                this@ChaptersList.context?.getString(
                                        com.hifnawy.quran.shared.R.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(bytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                decimalFormat.format(
                                        fileSize.toFloat() / (1024 * 1024)
                                )
                            } مب. (${
                                decimalFormat.format(progress)
                            }٪)"
                        }

                        DownloadWorkManager.DownloadStatus.DOWNLOADING -> {
                            downloadDialogChapterProgress.progress = progress.toInt()
                            downloadDialogChapterDownloadMessage.text = "${
                                context.getString(
                                        com.hifnawy.quran.shared.R.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(bytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                decimalFormat.format(
                                        fileSize.toFloat() / (1024 * 1024)
                                )
                            } مب. (${
                                decimalFormat.format(progress)
                            }٪)"
                        }

                        DownloadWorkManager.DownloadStatus.FILE_EXISTS,
                        DownloadWorkManager.DownloadStatus.FINISHED_DOWNLOAD -> {
                            val chaptersDownloadProgress =
                                (currentChapterIndex.toFloat() / chaptersListAdapter.itemCount.toFloat()) * 100f

                            downloadDialogChapterProgress.progress = progress.toInt()
                            downloadDialogChapterDownloadMessage.text = "${
                                context.getString(
                                        com.hifnawy.quran.shared.R.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(bytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                decimalFormat.format(
                                        fileSize.toFloat() / (1024 * 1024)
                                )
                            } مب. (${decimalFormat.format(progress)}٪)"

                            downloadDialogAllChaptersProgress.progress = chaptersDownloadProgress.toInt()
                            downloadDialogAllChaptersDownloadMessage.text =
                                "${
                                    context.getString(
                                            com.hifnawy.quran.shared.R.string.loading_all_chapters,
                                            decimalFormat.format(chaptersDownloadProgress)
                                    )
                                }\n${
                                    decimalFormat.format(currentChapterIndex)
                                } \\ ${
                                    decimalFormat.format(
                                            context.resources.getInteger(com.hifnawy.quran.shared.R.integer.quran_chapter_count)
                                    )
                                }"
                        }

                        DownloadWorkManager.DownloadStatus.DOWNLOAD_ERROR -> {
                            val chaptersDownloadProgress =
                                (currentChapterIndex.toFloat() / chaptersListAdapter.itemCount.toFloat()) * 100f

                            downloadDialogChapterProgress.progress = 100
                            downloadDialogChapterDownloadMessage.setTextColor(Color.RED)
                            downloadDialogChapterDownloadMessage.text = "${
                                context.getString(
                                        com.hifnawy.quran.shared.R.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(0)} مب. \\ ${decimalFormat.format(0)} مب. (${
                                decimalFormat.format(100f)
                            }٪)"

                            downloadDialogAllChaptersProgress.progress =
                                chaptersDownloadProgress.toInt()
                            downloadDialogAllChaptersDownloadMessage.text =
                                "${
                                    context.getString(
                                            com.hifnawy.quran.shared.R.string.loading_all_chapters,
                                            decimalFormat.format(chaptersDownloadProgress)
                                    )
                                }\n${
                                    decimalFormat.format(currentChapterIndex)
                                } \\ ${
                                    decimalFormat.format(
                                            context.resources.getInteger(com.hifnawy.quran.shared.R.integer.quran_chapter_count)
                                    )
                                }"
                        }

                        else -> Unit
                    }
                }
            }
    }

    private inner class MediaUpdatesReceiver : BroadcastReceiver() {

        var lastMediaPlaying = false
        var lastChapter: Chapter? = null
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.hasCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)) return
            val reciter =
                intent.getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name) ?: return
            val currentChapter =
                intent.getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name) ?: return
            val isMediaPlaying =
                intent.getBooleanExtra(Constants.IntentDataKeys.IS_MEDIA_PLAYING.name, false)

            if ((currentChapter.id != lastChapter?.id) || (isMediaPlaying != lastMediaPlaying)) {

                if (isMediaPlaying) {
                    chaptersListAdapter.notifyDataSetChanged()
                    // val chapter = chapters.find { chapter -> chapter.id == currentChapter.id }
                    // chapter?.let {
                    //     chaptersListAdapter.notifyItemChanged(chapters.indexOf(it))
                    // }
                }

                lastMediaPlaying = isMediaPlaying
                lastChapter = currentChapter
            }
        }
    }
}