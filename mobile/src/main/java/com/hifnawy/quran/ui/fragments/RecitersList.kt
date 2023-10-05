package com.hifnawy.quran.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.hifnawy.quran.R
import com.hifnawy.quran.adapters.RecitersListAdapter
import com.hifnawy.quran.databinding.FragmentRecitersListBinding
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.ui.activities.MainActivity


/**
 * A simple [Fragment] subclass.
 */
class RecitersList : Fragment() {
    private lateinit var binding: FragmentRecitersListBinding
    private val parentActivity: MainActivity by lazy {
        (activity as MainActivity)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        var reciters: List<Reciter>
        var recitersListAdapter: RecitersListAdapter

        parentActivity.supportActionBar?.apply {
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"

            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.reciters)}"
        }

        // Inflate the layout for this fragment
        binding = FragmentRecitersListBinding.inflate(inflater, container, false)

        with(binding) {
            recitersListAdapter = RecitersListAdapter(
                root.context, ArrayList(parentActivity.reciters)
            ) { position, reciter, itemView ->
                Log.d(
                    this@RecitersList::class.simpleName,
                    "clicked on $position: ${reciter.name_ar} ${itemView.recitationStyle.text}"
                )

                reciterSearch.text = null

                with(parentFragmentManager.beginTransaction()) {
                    hide(this@RecitersList)
                    addToBackStack(this@RecitersList::class.qualifiedName)
                    add(parentActivity.binding.fragmentContainer.id, ChaptersList(reciter))
                    commit()
                }
            }

            recitersList.layoutManager = LinearLayoutManager(root.context)
            recitersList.adapter = recitersListAdapter

            reciterSearch.addTextChangedListener(onTextChanged = { charSequence, _, _, _ ->
                if (charSequence.toString().isEmpty()) {
                    recitersListAdapter.setReciters(parentActivity.reciters)
                } else {
                    val searchResults = parentActivity.reciters.filter { reciter ->
                        return@filter reciter.name_ar.contains(charSequence.toString())
                    }

                    if (searchResults.isNotEmpty()) {
                        recitersListAdapter.setReciters(searchResults)
                    } else {
                        recitersListAdapter.clear()
                    }
                }
            })

            return root
        }
    }
}