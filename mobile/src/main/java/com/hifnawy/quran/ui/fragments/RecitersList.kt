package com.hifnawy.quran.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.hifnawy.quran.R
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
    private lateinit var navController: NavController

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        var reciters: List<Reciter>
        var recitersListAdapter: RecitersListAdapter

        // Inflate the layout for this fragment
        binding = FragmentRecitersListBinding.inflate(inflater, container, false)
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
            subtitle = "   ${getString(R.string.reciters)}"
        }

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

                    navController.navigate(
                        directions = RecitersListDirections.actionToChaptersList(
                            reciter = reciter
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    recitersList.layoutManager = LinearLayoutManager(root.context)
                    recitersList.adapter = recitersListAdapter

                    reciterSearch.addTextChangedListener(onTextChanged = { charSequence, _, _, _ ->
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