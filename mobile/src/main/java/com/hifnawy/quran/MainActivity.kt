package com.hifnawy.quran

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.google.android.material.color.DynamicColors
import com.hifnawy.quran.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        setSupportActionBar(binding.appToolbar)
        // window.requestFeature(Window.FEATURE_ACTION_BAR)
        // supportActionBar?.elevation = 0.0f
        // supportActionBar?.setDisplayHomeAsUpEnabled(false)
        // supportActionBar?.setDisplayShowTitleEnabled(true)

        // calling this activity's function to
        // use ActionBar utility methods
        val actionBar = supportActionBar

        actionBar?.apply {
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"

            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.reciters)}"

            // adding icon in the ActionBar
            setIcon(R.mipmap.ic_quran_mobile_round)

            // methods to display the icon in the ActionBar
            setDisplayUseLogoEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    override fun onResume() {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            add(R.id.fragmentContainer, RecitersList::class.java, null)
        }
        super.onResume()
    }
}