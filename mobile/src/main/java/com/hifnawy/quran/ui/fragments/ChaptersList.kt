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
import com.hifnawy.quran.R
import com.hifnawy.quran.adapters.ChaptersListAdapter
import com.hifnawy.quran.databinding.FragmentChaptersListBinding
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getChapterAudioFile
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.Utilities
import com.hifnawy.quran.shared.tools.Utilities.Companion.downloadFile
import com.hifnawy.quran.ui.activities.MainActivity
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A simple [Fragment] subclass.
 */
class ChaptersList(private val reciter: Reciter, private val chapter: Chapter? = null) : Fragment() {

    private val parentActivity: MainActivity by lazy { (activity as MainActivity) }
    private val mediaService by lazy { parentActivity.mediaService }
    private lateinit var binding: FragmentChaptersListBinding

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        var chaptersListAdapter: ChaptersListAdapter

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

        with(binding) {
            chaptersListAdapter = ChaptersListAdapter(
                    root.context, ArrayList(parentActivity.chapters)
            ) { position, chapter, itemView ->
                Log.d(
                        this@ChaptersList::class.simpleName,
                        "clicked on $position: ${chapter.translated_name?.name} ${itemView.verseCount.text}"
                )

                chapterSearch.text = null

                with(parentFragmentManager.beginTransaction()) {
                    hide(this@ChaptersList)
                    addToBackStack(this@ChaptersList::class.qualifiedName)
                    add(parentActivity.binding.fragmentContainer.id, MediaPlayback(reciter, chapter))
                    commit()
                }

                mediaService.prepareMedia(reciter, chapter)
            }

            chaptersList.layoutManager =
                GridLayoutManager(root.context, 3, GridLayoutManager.VERTICAL, false)
            chaptersList.adapter = chaptersListAdapter

            chapterSearch.addTextChangedListener(onTextChanged = { charSequence, _, _, _ ->
                if (charSequence.toString().isEmpty()) {
                    chaptersListAdapter.setChapters(parentActivity.chapters)
                } else {
                    val searchResults = parentActivity.chapters.filter { chapter ->
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
                val context = binding.root.context

                val (dialog, dialogBinding) = DialogBuilder.prepareDownloadDialog(
                        binding.root.context,
                        DialogBuilder.DownloadType.BULK
                )

                with(dialogBinding) {
                    downloadDialogChapterProgress.min = 0
                    downloadDialogChapterProgress.max = 100
                    downloadDialogChapterProgress.progress = 0
                    downloadDialogAllChaptersProgress.min = 0
                    downloadDialogAllChaptersProgress.max = 100
                    downloadDialogAllChaptersProgress.progress = 0
                    val decimalFormat =
                        DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))
                    var chaptersDownloaded = 0

                    dialog.show()
                    with(dialogBinding) {
                        downloadDialogAllChaptersProgress.progress = 0
                        downloadDialogAllChaptersDownloadMessage.text =
                            context.getString(
                                    com.hifnawy.quran.shared.R.string.loading_all_chapters,
                                    decimalFormat.format(0)
                            )
                    }

                    lifecycleScope.launch(Dispatchers.IO) {
                        for (chapter in chaptersListAdapter.getChapters()) {
                            val chapterAudioFile = getChapterAudioFile(reciter.id, chapter.id)

                            downloadFile(
                                    context, URL(chapterAudioFile?.audio_url), reciter, chapter
                            ) { downloadStatus, bytesDownloaded, fileSize, percentage, _ ->
                                withContext(Dispatchers.Main) {
                                    when (downloadStatus) {
                                        Utilities.Companion.DownloadStatus.STARTING_DOWNLOAD -> {
                                            downloadDialogChapterProgress.progress =
                                                percentage.toInt()
                                            downloadDialogChapterDownloadMessage.text = "${
                                                this@ChaptersList.context?.getString(
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

                                        Utilities.Companion.DownloadStatus.DOWNLOADING -> {
                                            downloadDialogChapterProgress.progress =
                                                percentage.toInt()
                                            downloadDialogChapterDownloadMessage.text = "${
                                                this@ChaptersList.context?.getString(
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
                                            chaptersDownloaded++
                                            val chaptersDownloadProgress =
                                                (chaptersDownloaded.toFloat() / chaptersListAdapter.itemCount.toFloat()) * 100f

                                            downloadDialogChapterProgress.progress =
                                                percentage.toInt()
                                            downloadDialogChapterDownloadMessage.text = "${
                                                this@ChaptersList.context?.getString(
                                                        com.hifnawy.quran.shared.R.string.loading_chapter,
                                                        chapter.name_arabic
                                                )
                                            }\n${decimalFormat.format(bytesDownloaded.toFloat() / (1024 * 1024))} مب. \\ ${
                                                decimalFormat.format(
                                                        fileSize.toFloat() / (1024 * 1024)
                                                )
                                            } مب. (${decimalFormat.format(percentage)}٪)"

                                            downloadDialogAllChaptersProgress.progress =
                                                chaptersDownloadProgress.toInt()
                                            downloadDialogAllChaptersDownloadMessage.text =
                                                context.getString(
                                                        com.hifnawy.quran.shared.R.string.loading_all_chapters,
                                                        decimalFormat.format(chaptersDownloadProgress)
                                                )
                                        }

                                        Utilities.Companion.DownloadStatus.DOWNLOAD_ERROR -> Unit
                                    }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                        }
                    }
                }
            }

            return root
        }
    }
}