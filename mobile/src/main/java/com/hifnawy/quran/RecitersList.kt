package com.hifnawy.quran

import android.os.Bundle
import android.util.Log
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

                recitersListAdapter = RecitersListAdapter(
                    root.context,
                    ArrayList(reciters)
                ) { position, reciter, itemView ->
                    Log.d(
                        this@RecitersList.javaClass.canonicalName,
                        "clicked on $position: ${reciter.translated_name?.name} ${itemView.recitationStyle.text}"
                    )
                    // Snackbar.make(
                    //     this@RecitersList.requireView(),
                    //     "clicked on $position: ${reciter.translated_name?.name} ${itemView.recitationStyle.text}",
                    //     Snackbar.LENGTH_INDEFINITE
                    // ).show()
                    // Toast.makeText(
                    //     this@RecitersList.context,
                    //     "clicked on $position: ${reciter.reciter_name} ${itemView.recitationStyle.text}",
                    //     Toast.LENGTH_LONG
                    // ).show()
                }

                withContext(Dispatchers.Main) {
                    recitersList.layoutManager = LinearLayoutManager(root.context)
                    recitersList.adapter = recitersListAdapter

                    /*
                    reciterSearch.addTextChangedListener { text ->
                        if (text.toString().isEmpty()) {
                            recitersListAdapter.setReciters(reciters)
                        } else {
                            val searchResults = reciters.filter { reciter ->
                                return@filter if (reciter.translated_name != null) {
                                    reciter.translated_name!!.name.contains(text.toString())
                                } else {
                                    reciter.reciter_name.contains(text.toString())
                                }
                            }

                            if (searchResults.isNotEmpty()) {
                                recitersListAdapter.setReciters(searchResults)
                            } else {
                                recitersListAdapter.clear()
                            }
                        }
                    }
                    */

                    reciterSearch.addTextChangedListener({ charSequence, _, _, _ ->
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