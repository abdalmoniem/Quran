package com.hifnawy.quran.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.color.DynamicColors
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        setSupportActionBar(binding.appToolbar)

        navController = findNavController(R.id.fragment_container)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        supportActionBar?.apply {
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
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}