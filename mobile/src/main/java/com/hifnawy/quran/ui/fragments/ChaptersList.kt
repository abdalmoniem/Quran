package com.hifnawy.quran.ui.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.hifnawy.quran.shared.managers.DownloadWorkManager
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.ui.activities.MainActivity
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID

/**
 * A simple [Fragment] subclass.
 */
class ChaptersList(private val reciter: Reciter, private val chapter: Chapter? = null) : Fragment() {

    private val parentActivity: MainActivity by lazy { (activity as MainActivity) }
    private val workManager by lazy { WorkManager.getInstance(binding.root.context) }
    private var chapters: List<Chapter> = mutableListOf()
    private lateinit var binding: FragmentChaptersListBinding
    private lateinit var chaptersListAdapter: ChaptersListAdapter

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        parentActivity.supportActionBar?.apply {
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"
            // providing subtitle for the ActionBar
            subtitle =
                "   ${getString(R.string.chapters)}: ${reciter.name_ar} ${if (reciter.style?.style != null) "(${reciter.style?.style})" else ""}"

            show()
        }
        // Inflate the layout for this fragment
        binding = FragmentChaptersListBinding.inflate(inflater, container, false)

        lifecycleScope.launch {
            chapters =
                lifecycleScope.async(context = Dispatchers.IO) { QuranAPI.getChaptersList() }.await()

            with(binding) {
                chaptersListAdapter = ChaptersListAdapter(
                        root.context, ArrayList(chapters)
                ) { position, chapter, itemView ->
                    Log.d(
                            ChaptersList::class.simpleName,
                            "clicked on $position: ${chapter.translated_name?.name} ${itemView.verseCount.text}"
                    )

                    chapterSearch.text = null

                    parentActivity.binding.mediaPlaybackFragmentContainer.visibility = View.VISIBLE
                    with(parentFragmentManager.beginTransaction()) {
                        addToBackStack(ChaptersList::class.simpleName)
                        add(
                                parentActivity.binding.mediaPlaybackFragmentContainer.id,
                                MediaPlayback(reciter, chapter)
                        )
                        commit()
                    }
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
                        .addTag(getString(com.hifnawy.quran.shared.R.string.downloadWorkManagerUniqueWorkName))
                        .build()

                    observeWorker(downloadWorkRequest.id)

                    workManager.enqueueUniqueWork(
                            getString(com.hifnawy.quran.shared.R.string.downloadWorkManagerUniqueWorkName),
                            ExistingWorkPolicy.REPLACE,
                            downloadWorkRequest
                    )
                }
            }
        }

        return binding.root
    }

    @SuppressLint("SetTextI18n")
    private fun observeWorker(requestID: UUID) {
        val context = binding.root.context

        val (dialog, dialogBinding) = DialogBuilder.prepareDownloadDialog(
                binding.root.context,
                DialogBuilder.DownloadType.BULK
        )

        dialog.show()

        with(dialogBinding) {
            dialogBinding.downloadDialogCancelDownload.setOnClickListener {
                workManager.cancelUniqueWork(getString(com.hifnawy.quran.shared.R.string.downloadWorkManagerUniqueWorkName))
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
                if (workInfo.state == WorkInfo.State.FAILED) return@observe
                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                    dialog.dismiss()
                    return@observe
                }
                val currentChapterJSON =
                    workInfo.progress.getString(DownloadWorkManager.DownloadWorkerInfo.CURRENT_CHAPTER_NUMBER.name)
                        ?: return@observe
                val currentChapter = DownloadWorkManager.toChapter(currentChapterJSON)
                val downloadStatus = DownloadWorkManager.DownloadStatus.valueOf(
                        workInfo.progress.getString(DownloadWorkManager.DownloadWorkerInfo.DOWNLOAD_STATUS.name)
                            ?: return@observe
                )
                val bytesDownloaded = workInfo.progress.getLong(
                        DownloadWorkManager.DownloadWorkerInfo.BYTES_DOWNLOADED.name,
                        -1L
                )
                val fileSize = workInfo.progress.getInt(
                        DownloadWorkManager.DownloadWorkerInfo.FILE_SIZE.name,
                        -1
                )
                val progress = workInfo.progress.getFloat(
                        DownloadWorkManager.DownloadWorkerInfo.PROGRESS.name,
                        -1f
                )
                val decimalFormat =
                    DecimalFormat(
                            "#.#",
                            DecimalFormatSymbols.getInstance(Locale("ar", "EG"))
                    )
                val currentChapterIndex = chaptersListAdapter.getChapters()
                    .indexOf(chaptersListAdapter.getChapters().find { chapter ->
                        chapter.id == currentChapter.id
                    }) + 1

                with(dialogBinding) {
                    when (downloadStatus) {
                        DownloadWorkManager.DownloadStatus.STARTING_DOWNLOAD -> {
                            downloadDialogChapterProgress.progress =
                                progress.toInt()
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
                                            114
                                    )
                                }"
                        }

                        DownloadWorkManager.DownloadStatus.DOWNLOAD_ERROR -> Unit
                        else -> Unit
                    }
                }
            }
    }
}