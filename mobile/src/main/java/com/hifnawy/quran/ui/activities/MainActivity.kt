package com.hifnawy.quran.ui.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ActivityMainBinding
import com.hifnawy.quran.shared.api.APIRequester
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
import com.hifnawy.quran.ui.widgets.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    var reciters: List<Reciter> = mutableListOf()
    var chapters: List<Chapter> = mutableListOf()

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        setSupportActionBar(binding.appToolbar)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 93)
            }
        }

        supportActionBar?.apply {
            // disable back button
            setHomeButtonEnabled(false)
            setDisplayHomeAsUpEnabled(false)

            // adding icon in the ActionBar
            setIcon(R.mipmap.ic_quran_mobile_round)

            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"

            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.reciters)}"
        }
    }

    override fun onResume() {
        var reciter: Reciter?
        var chapter: Chapter?

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val graphInflater = navHostFragment.navController.navInflater
        val graph = graphInflater.inflate(R.navigation.navigation_graph)
        val navController = navHostFragment.navController

        with(intent) {
            if (hasCategory(NowPlaying::class.simpleName)) {
                reciter = getSerializableExtra<Reciter>(Constants.IntentDataKeys.RECITER.name)
                chapter = getSerializableExtra<Chapter>(Constants.IntentDataKeys.CHAPTER.name)

                graph.setStartDestination(R.id.media_playback)
                navController.graph = graph

                navController.navigate(
                    R.id.action_to_media_playback_from_notification,
                    Bundle().apply {
                        putSerializable(getString(R.string.nav_graph_reciter_argument), reciter!!)
                        putSerializable(getString(R.string.nav_graph_chapter_argument), chapter!!)
                    })
            } else {
                MediaService.instance?.run {
                    if (!isMediaPlaying) {
                        graph.setStartDestination(R.id.reciters_list)

                        getData {
                            navController.graph = graph
                        }
                    }
                } ?: graph.setStartDestination(R.id.reciters_list)

                getData {
                    navController.graph = graph
                }
            }
        }

        super.onResume()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 93) {
            if (grantResults.isNotEmpty() && (grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
                val snackbar = Snackbar.make(
                    binding.root,
                    getString(R.string.notification_permission_required),
                    Snackbar.LENGTH_INDEFINITE
                )

                snackbar.setAction("الذهاب للإعدادات") {
                    try {
                        //Open the specific App Info page:
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    } catch (e: ActivityNotFoundException) {
                        //Open the generic Apps page:
                        val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                        startActivity(intent)
                    }
                }

                ViewCompat.setLayoutDirection(snackbar.view, ViewCompat.LAYOUT_DIRECTION_RTL)

                snackbar.show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun getData(onCompleteCallback: () -> Unit) {
        lifecycleScope.launch {
            reciters =
                lifecycleScope.async(context = Dispatchers.IO) { APIRequester.getRecitersList() }
                    .await()
            chapters =
                lifecycleScope.async(context = Dispatchers.IO) { APIRequester.getChaptersList() }
                    .await()

            // lifecycleScope.async(context = Dispatchers.IO) {
            //     updateChapterPaths(
            //         this@MainActivity, reciters, chapters
            //     )
            // }.await()
            //
            // Log.d(Utilities::class.simpleName, "SharedPrefs Updated!!!")

            onCompleteCallback()
        }
    }
}