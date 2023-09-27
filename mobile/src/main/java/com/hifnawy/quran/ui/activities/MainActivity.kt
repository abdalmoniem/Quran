package com.hifnawy.quran.ui.activities

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.color.DynamicColors
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ActivityMainBinding
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import java.io.Serializable


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        setSupportActionBar(binding.appToolbar)

        navController = findNavController(R.id.fragment_container)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        supportActionBar?.apply {
            // methods to display the icon in the ActionBar
            // setDisplayUseLogoEnabled(true)
            // setDisplayShowHomeEnabled(true)
            // setDisplayShowTitleEnabled(true)

            // disable back button
            setDisplayHomeAsUpEnabled(false)

            // adding icon in the ActionBar
            setIcon(R.mipmap.ic_quran_mobile_round)

            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"

            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.reciters)}"
        }

        val bundle = intent.extras

        if (bundle != null) {
            with(bundle) {
                val destinationFragment = bundle.getInt("DESTINATION", -1)
                val reciter = bundle.getSerializable<Reciter>("RECITER")
                val chapter = bundle.getSerializable<Chapter>("CHAPTER")
                if ((destinationFragment != -1) and (reciter != null) and (chapter != null)) {
                    navController.navigate(
                        R.id.action_to_chapter_play_from_notification,
                        Bundle().apply {
                            putSerializable("reciter", reciter!!)
                            putSerializable("chapter", chapter!!)
                        }
                    )
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    private inline fun <reified GenericType : Serializable> Bundle.getSerializable(key: String): GenericType? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializable(
                key,
                GenericType::class.java
            )

            else -> @Suppress("DEPRECATION") getSerializable(key) as? GenericType
        }
}