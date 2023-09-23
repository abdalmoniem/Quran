package com.hifnawy.quran

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.hifnawy.quran.adapters.RecitersListAdapter
import com.hifnawy.quran.databinding.FragmentRecitersListBinding
import com.hifnawy.quran.shared.api.APIRequester
import com.hifnawy.quran.shared.model.Reciter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * A simple [Fragment] subclass.
 */
class RecitersList : Fragment() {
    private lateinit var binding: FragmentRecitersListBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        var reciters: List<Reciter>
        var recitersListAdapter: RecitersListAdapter

        // Inflate the layout for this fragment
        binding = FragmentRecitersListBinding.inflate(inflater, container, false)

        with(binding) {
            CoroutineScope(Dispatchers.IO).launch {
                reciters = APIRequester.getRecitersList()

                recitersListAdapter = RecitersListAdapter(root.context, ArrayList(reciters))

                withContext(Dispatchers.Main) {
                    recitersList.layoutManager = LinearLayoutManager(root.context)
                    recitersList.adapter = recitersListAdapter

                    reciterSearch.addTextChangedListener({ charSequence, start, before, count ->
                        if (charSequence.toString().isEmpty()) {
                            recitersListAdapter.setReciters(reciters)
                        } else {
                            val searchResults = reciters.filter { reciter ->
                                return@filter if (reciter.translated_name != null) {
                                    reciter.translated_name!!.name.contains(charSequence.toString())
                                } else {
                                    reciter.reciter_name.contains(charSequence.toString())
                                }
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

            return root
        }
    }
}