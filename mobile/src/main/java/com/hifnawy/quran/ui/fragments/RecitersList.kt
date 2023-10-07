package com.hifnawy.quran.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hifnawy.quran.R
import com.hifnawy.quran.adapters.RecitersListAdapter
import com.hifnawy.quran.databinding.FragmentRecitersListBinding
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.ui.activities.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass.
 */
class RecitersList : Fragment() {

    private val parentActivity: MainActivity by lazy {
        (activity as MainActivity)
    }
    private var reciters: List<Reciter> = mutableListOf()
    private lateinit var binding: FragmentRecitersListBinding

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        var recitersListAdapter: RecitersListAdapter

        parentActivity.supportActionBar?.apply {
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"
            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.reciters)}"

            show()
        }
        // Inflate the layout for this fragment
        binding = FragmentRecitersListBinding.inflate(inflater, container, false)

        lifecycleScope.launch {
            reciters =
                lifecycleScope.async(context = Dispatchers.IO) { QuranAPI.getRecitersList() }.await()

            with(binding) {
                recitersListAdapter = RecitersListAdapter(
                        root.context, ArrayList(reciters)
                ) { position, reciter, itemView ->
                    Log.d(
                            RecitersList::class.simpleName,
                            "clicked on $position: ${reciter.name_ar} ${itemView.recitationStyle.text}"
                    )

                    reciterSearch.text = null

                    with(parentFragmentManager.beginTransaction()) {
                        hide(this@RecitersList)
                        addToBackStack(RecitersList::class.qualifiedName)
                        add(parentActivity.binding.fragmentContainer.id, ChaptersList(reciter))
                        commit()
                    }
                }

                recitersList.layoutManager = LinearLayoutManager(root.context)
                recitersList.adapter = recitersListAdapter

                reciterSearch.addTextChangedListener(onTextChanged = { charSequence, _, _, _ ->
                    if (charSequence.toString().isEmpty()) {
                        recitersListAdapter.setReciters(reciters)
                    } else {
                        val searchResults = reciters.filter { reciter ->
                            return@filter reciter.name_ar.contains(charSequence.toString())
                        }

                        if (searchResults.isNotEmpty()) {
                            recitersListAdapter.setReciters(searchResults)
                        } else {
                            recitersListAdapter.clear()
                        }
                    }
                })
            }
        }

        return binding.root
    }
}