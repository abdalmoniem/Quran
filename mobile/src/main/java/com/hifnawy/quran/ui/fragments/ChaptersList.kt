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
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.hifnawy.quran.R
import com.hifnawy.quran.adapters.ChaptersListAdapter
import com.hifnawy.quran.databinding.FragmentChaptersListBinding
import com.hifnawy.quran.shared.QuranMediaService
import com.hifnawy.quran.shared.api.APIRequester
import com.hifnawy.quran.shared.api.APIRequester.Companion.getChapter
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.tools.Utilities.Companion.downloadFile
import com.hifnawy.quran.ui.activities.MainActivity
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
class ChaptersList : Fragment() {
    private lateinit var binding: FragmentChaptersListBinding
    private lateinit var navController: NavController
    private val parentActivity: MainActivity by lazy {
        (activity as MainActivity)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val reciter = ChaptersListArgs.fromBundle(requireArguments()).reciter

        var chapters: List<Chapter>
        var chaptersListAdapter: ChaptersListAdapter

        parentActivity.supportActionBar?.apply {
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"

            // providing subtitle for the ActionBar
            subtitle =
                "   ${getString(R.string.chapters)}: ${if (reciter.translated_name != null) reciter.translated_name!!.name else reciter.reciter_name}"
        }

        // Inflate the layout for this fragment
        binding = FragmentChaptersListBinding.inflate(inflater, container, false)
        navController = findNavController()

        with(binding) {
            downloadDialog.visibility = View.GONE

            lifecycleScope.launch(context = Dispatchers.IO) {
                chapters = APIRequester.getChaptersList()

                chaptersListAdapter = ChaptersListAdapter(
                    root.context, ArrayList(chapters)
                ) { position, chapter, itemView ->
                    Log.d(
                        this@ChaptersList.javaClass.canonicalName,
                        "clicked on $position: ${chapter.translated_name?.name} ${itemView.verseCount.text}"
                    )

                    chapterSearch.text = null
                    navController.navigate(ChaptersListDirections.actionToChapterPlay(reciter, chapter))
                }

                downloadDialogCancelDownload.setOnClickListener {
                    QuranMediaService.startDownload = false
                    downloadDialog.visibility = View.GONE
                }

                downloadAllChapters.setOnClickListener {
                    downloadDialog.visibility = View.VISIBLE

                    downloadDialogChapterProgress.valueFrom = 0f
                    downloadDialogChapterProgress.valueTo = 100f
                    downloadDialogChapterProgress.value = 0f
                    downloadDialogAllChaptersProgress.valueFrom = 0f
                    downloadDialogAllChaptersProgress.valueTo = 100f
                    downloadDialogAllChaptersProgress.value = 0f

                    val decimalFormat =
                        DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))

                    var chaptersDownloaded = 0

                    lifecycleScope.launch(Dispatchers.IO) {
                        QuranMediaService.startDownload = true
                        for (chapter in chaptersListAdapter.getChapters()) {
                            if (downloadDialog.visibility == View.VISIBLE) {
                                withContext(Dispatchers.Main) {
                                    downloadDialogChapterDownloadMessage.text = "${
                                        this@ChaptersList.context?.getString(
                                            com.hifnawy.quran.shared.R.string.loading_chapter,
                                            chapter.name_arabic
                                        )
                                    }\n…"
                                    downloadDialogChapterProgress.value = 100f
                                }

                                val chapterAudioFile = getChapter(reciter.id, chapter.id)

                                context?.let { context ->
                                    downloadFile(
                                        context, URL(chapterAudioFile?.audio_url), reciter, chapter
                                    ) { bytesDownloaded, fileSize, percentage ->
                                        withContext(Dispatchers.Main) {
                                            with(binding) {
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
                                                downloadDialogChapterProgress.value = percentage
                                            }
                                        }
                                    }
                                }
                            }

                            chaptersDownloaded++

                            lifecycleScope.launch(Dispatchers.Main) {
                                val chaptersDownloadProgress =
                                    (chaptersDownloaded.toFloat() / chaptersListAdapter.itemCount.toFloat()) * 100f
                                downloadDialogAllChaptersProgress.value = chaptersDownloadProgress
                                downloadDialogAllChaptersDownloadMessage.text = context?.getString(
                                    com.hifnawy.quran.shared.R.string.loading_all_chapters,
                                    decimalFormat.format(chaptersDownloadProgress)
                                )
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
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
                }
            }

            return root
        }
    }

    override fun onDestroyView() {
        QuranMediaService.startDownload = false

        super.onDestroyView()
    }

    override fun onDestroy() {
        QuranMediaService.startDownload = false

        super.onDestroy()
    }
}