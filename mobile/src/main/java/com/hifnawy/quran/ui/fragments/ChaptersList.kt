package com.hifnawy.quran.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.hifnawy.quran.R
import com.hifnawy.quran.adapters.ChaptersListAdapter
import com.hifnawy.quran.databinding.FragmentChaptersListBinding
import com.hifnawy.quran.shared.api.APIRequester
import com.hifnawy.quran.shared.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A simple [Fragment] subclass.
 */
class ChaptersList : Fragment() {
    private lateinit var binding: FragmentChaptersListBinding
    private lateinit var navController: NavController

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        var chapters: List<Chapter>
        var chaptersListAdapter: ChaptersListAdapter

        // Inflate the layout for this fragment
        binding = FragmentChaptersListBinding.inflate(inflater, container, false)
        navController = findNavController()

        (activity as AppCompatActivity).supportActionBar?.apply {
            // // methods to display the icon in the ActionBar
            // setDisplayUseLogoEnabled(true)
            // setDisplayShowHomeEnabled(true)
            // setDisplayShowTitleEnabled(true)

            // adding icon in the ActionBar
            // setIcon(R.mipmap.ic_quran_mobile_round)

            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"

            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.chapters)}"
        }

        with(binding) {
            lifecycleScope.launch(context = Dispatchers.IO) {
                chapters = APIRequester.getChaptersList()

                chaptersListAdapter = ChaptersListAdapter(
                    root.context, ArrayList(chapters)
                ) { position, chapter, itemView ->
                    Log.d(
                        this@ChaptersList.javaClass.canonicalName,
                        "clicked on $position: ${chapter.translated_name?.name} ${itemView.verseCount.text}"
                    )

                    val reciter = ChaptersListArgs.fromBundle(requireArguments()).reciter
                    navController.navigate(ChaptersListDirections.actionToChapterPlay(reciter, chapter))
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
}