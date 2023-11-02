package com.hifnawy.quran.ui.fragments

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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
        // Inflate the layout for this fragment
        binding = FragmentRecitersListBinding.inflate(inflater, container, false)

        lifecycleScope.launch {
            reciters =
                lifecycleScope.async(context = Dispatchers.IO) { QuranAPI.getRecitersList(binding.root.context) }
                    .await()

            with(binding) {
                recitersListAdapter = RecitersListAdapter(
                        root.context, ArrayList(reciters)
                ) { _, reciter, _ ->
                    reciterSearch.text = null
                    reciterSearch.clearFocus()
                    val inputMethodManager =
                        requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
                    // Hide:
                    inputMethodManager.hideSoftInputFromWindow(root.windowToken, 0)

                    findNavController().navigate(RecitersListDirections.toChaptersList(reciter))
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

    override fun onResume() {
        parentActivity.supportActionBar?.apply {
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"
            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.reciters)}"

            show()
        }

        super.onResume()
    }
}