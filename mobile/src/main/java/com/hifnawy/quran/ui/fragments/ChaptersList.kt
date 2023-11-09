package com.hifnawy.quran.ui.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.work.WorkManager
import com.hifnawy.quran.R
import com.hifnawy.quran.adapters.ChaptersListAdapter
import com.hifnawy.quran.databinding.DownloadDialogBinding
import com.hifnawy.quran.databinding.FragmentChaptersListBinding
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.managers.DownloadWorkManager.DownloadStatus
import com.hifnawy.quran.shared.managers.MediaManager
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants.IntentDataKeys
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions
import com.hifnawy.quran.shared.model.Constants.ServiceUpdates
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.ui.activities.MainActivity
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID
import com.hifnawy.quran.shared.R as sharedR

private val TAG = ChaptersList::class.java.simpleName

/**
 * A simple [Fragment] subclass.
 */
class ChaptersList : Fragment() {

    private val parentActivity: MainActivity by lazy { (activity as MainActivity) }
    private val reciter by lazy { ChaptersListArgs.fromBundle(requireArguments()).reciter }
    private val workManager by lazy { WorkManager.getInstance(binding.root.context) }
    private val downloadRequestID by lazy { UUID.fromString(getString(sharedR.string.BULK_DOWNLOAD_WORK_REQUEST_ID)) }
    private lateinit var binding: FragmentChaptersListBinding
    private lateinit var chaptersListAdapter: ChaptersListAdapter
    private lateinit var chaptersDownloadProgressReceiver: ChaptersDownloadProgressReceiver

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentChaptersListBinding.inflate(inflater, container, false)

        MediaManager.getInstance(binding.root.context)
            .whenChaptersReady { chapters ->
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

                    downloadAllChapters.setOnClickListener(::startDownload)
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

    @SuppressLint("SetTextI18n", "UnspecifiedRegisterReceiverFlag")
    private fun startDownload(downloadAllChaptersButton: View) {
        val context = binding.root.context

        val (dialog, dialogBinding) = DialogBuilder.prepareDownloadDialog(
                binding.root.context,
                DialogBuilder.DownloadType.BULK
        )
        downloadAllChaptersButton.isEnabled = false
        parentActivity.startForegroundService(Intent(
                context, MediaService::class.java
        ).apply {
            action = MediaServiceActions.DOWNLOAD_CHAPTERS.name

            putExtra(IntentDataKeys.RECITER.name, reciter)
        })

        with(dialogBinding) {
            dialogBinding.downloadDialogCancelDownload.setOnClickListener {
                workManager.cancelWorkById(downloadRequestID)
                dialog.dismiss()
                downloadAllChaptersButton.isEnabled = true
                parentActivity.unregisterReceiver(chaptersDownloadProgressReceiver)
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
                            sharedR.string.loading_all_chapters,
                            decimalFormat.format(0)
                    )

            downloadDialogChapterProgress.progress = 0
            downloadDialogChapterDownloadMessage.text = "${
                context.getString(
                        sharedR.string.loading_chapter,
                        ""
                )
            }\n${decimalFormat.format(0)} مب. \\ ${
                decimalFormat.format(0)
            } مب. (${decimalFormat.format(0)}٪)"

            downloadDialogAllChaptersProgress.progress = 0
            downloadDialogAllChaptersDownloadMessage.text =
                    context.getString(
                            sharedR.string.loading_all_chapters,
                            decimalFormat.format(0)
                    )
        }

        if (!this::chaptersDownloadProgressReceiver.isInitialized) {
            chaptersDownloadProgressReceiver = ChaptersDownloadProgressReceiver(dialog, dialogBinding)
        } else {
            chaptersDownloadProgressReceiver.dialog = dialog
            chaptersDownloadProgressReceiver.dialogBinding = dialogBinding
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            parentActivity.registerReceiver(
                    chaptersDownloadProgressReceiver,
                    IntentFilter(getString(sharedR.string.quran_media_service_updates)).apply {
                        addCategory(ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_PROGRESS.name)
                        addCategory(ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_SUCCEED.name)
                        addCategory(ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_FAILED.name)
                    }, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            parentActivity.registerReceiver(
                    chaptersDownloadProgressReceiver,
                    IntentFilter(getString(sharedR.string.quran_media_service_updates)).apply {
                        addCategory(ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_PROGRESS.name)
                    })
        }

        dialog.show()
    }

    private inner class ChaptersDownloadProgressReceiver(
            var dialog: AlertDialog,
            var dialogBinding: DownloadDialogBinding
    ) :
            BroadcastReceiver() {

        val context: Context = binding.root.context
        val decimalFormat =
                DecimalFormat(
                        "#.#",
                        DecimalFormatSymbols.getInstance(Locale("ar", "EG"))
                )
        var downloadedChapterCount = 0

        @SuppressLint("SetTextI18n")
        override fun onReceive(receiverContext: Context?, intent: Intent) {
            if (intent.hasCategory(ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_SUCCEED.name)) {
                binding.downloadAllChapters.isEnabled = true
                dialog.dismiss()

                parentActivity.unregisterReceiver(this)
                return
            }

            if (intent.hasCategory(ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_FAILED.name)) {
                binding.downloadAllChapters.isEnabled = true
                dialog.dismiss()
                DialogBuilder.showErrorDialog(
                        binding.root.context, getString(R.string.connection_error_title),
                        getString(
                                R.string.downloading_chapters_error_message,
                                decimalFormat.format(context.resources.getInteger(com.hifnawy.quran.shared.R.integer.quran_chapter_count) - downloadedChapterCount)
                        ), "تمام"
                )

                parentActivity.unregisterReceiver(this)
                return
            }

            if (!intent.hasCategory(ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_PROGRESS.name)) return

            with(intent) {
                val reciter = getTypedSerializable<Reciter>(IntentDataKeys.RECITER.name)
                val currentChapter = getTypedSerializable<Chapter>(IntentDataKeys.CHAPTER.name) ?: return
                val currentChapterIndex =
                        getIntExtra(IntentDataKeys.CHAPTER_INDEX.name, -1)
                val currentChapterDownloadStatus =
                        getTypedSerializable<DownloadStatus>(IntentDataKeys.CHAPTER_DOWNLOAD_STATUS.name)
                        ?: return
                val currentChapterBytesDownloaded =
                        getLongExtra(IntentDataKeys.CHAPTER_DOWNLOADED_BYTES.name, -1L)
                val currentChapterFileSize = getIntExtra(IntentDataKeys.CHAPTER_DOWNLOAD_SIZE.name, -1)
                val currentChapterProgress =
                        getFloatExtra(IntentDataKeys.CHAPTER_DOWNLOAD_PROGRESS.name, -1f)
                val allChaptersProgress =
                        getFloatExtra(IntentDataKeys.CHAPTERS_DOWNLOAD_PROGRESS.name, -1f)

                Log.d(
                        TAG,
                        "Download Status: $currentChapterDownloadStatus" +
                        "\nReciter: ${reciter?.reciter_name}" +
                        "\nChapter: ${currentChapter.name_simple}" +
                        "\nIndex: $currentChapterIndex" +
                        "\nDownloaded: $currentChapterBytesDownloaded" +
                        "\nSize: $currentChapterFileSize" +
                        "\nChapter Progress: $currentChapterProgress" +
                        "\nChapters Progress: $allChaptersProgress"
                )

                with(dialogBinding) {
                    when (currentChapterDownloadStatus) {
                        DownloadStatus.STARTING_DOWNLOAD -> {
                            downloadDialogChapterProgress.progress = currentChapterProgress.toInt()
                            downloadDialogChapterDownloadMessage.setTextColor(android.graphics.Color.WHITE)
                            downloadDialogChapterDownloadMessage.text = "${
                                this@ChaptersList.context?.getString(
                                        sharedR.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(currentChapterBytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                decimalFormat.format(
                                        currentChapterFileSize.toFloat() / (1024 * 1024)
                                )
                            } مب. (${
                                decimalFormat.format(currentChapterProgress)
                            }٪)"
                        }

                        DownloadStatus.DOWNLOADING       -> {
                            downloadDialogChapterProgress.progress = currentChapterProgress.toInt()
                            downloadDialogChapterDownloadMessage.text = "${
                                context.getString(
                                        sharedR.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(currentChapterBytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                decimalFormat.format(
                                        currentChapterFileSize.toFloat() / (1024 * 1024)
                                )
                            } مب. (${
                                decimalFormat.format(currentChapterProgress)
                            }٪)"
                        }

                        DownloadStatus.FILE_EXISTS,
                        DownloadStatus.FINISHED_DOWNLOAD -> {
                            downloadedChapterCount += 1
                            downloadDialogChapterProgress.progress = currentChapterProgress.toInt()
                            downloadDialogChapterDownloadMessage.text = "${
                                context.getString(
                                        sharedR.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(currentChapterBytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                decimalFormat.format(
                                        currentChapterFileSize.toFloat() / (1024 * 1024)
                                )
                            } مب. (${decimalFormat.format(currentChapterProgress)}٪)"

                            downloadDialogAllChaptersProgress.progress = allChaptersProgress.toInt()
                            downloadDialogAllChaptersDownloadMessage.text =
                                    "${
                                        context.getString(
                                                sharedR.string.loading_all_chapters,
                                                decimalFormat.format(allChaptersProgress)
                                        )
                                    }\n${
                                        decimalFormat.format(currentChapterIndex)
                                    } \\ ${
                                        decimalFormat.format(
                                                context.resources.getInteger(sharedR.integer.quran_chapter_count)
                                        )
                                    }"
                        }

                        DownloadStatus.DOWNLOAD_ERROR    -> {
                            downloadDialogChapterProgress.progress = 100
                            downloadDialogChapterDownloadMessage.setTextColor(android.graphics.Color.RED)
                            downloadDialogChapterDownloadMessage.text = "${
                                context.getString(
                                        sharedR.string.loading_chapter,
                                        currentChapter.name_arabic
                                )
                            }\n${decimalFormat.format(0)} مب. \\ ${decimalFormat.format(0)} مب. (${
                                decimalFormat.format(100f)
                            }٪)"

                            downloadDialogAllChaptersProgress.progress =
                                    allChaptersProgress.toInt()
                            downloadDialogAllChaptersDownloadMessage.text =
                                    "${
                                        context.getString(
                                                sharedR.string.loading_all_chapters,
                                                decimalFormat.format(allChaptersProgress)
                                        )
                                    }\n${
                                        decimalFormat.format(currentChapterIndex)
                                    } \\ ${
                                        decimalFormat.format(
                                                context.resources.getInteger(sharedR.integer.quran_chapter_count)
                                        )
                                    }"
                        }

                        else                             -> Unit
                    }
                }
            }
        }
    }
}