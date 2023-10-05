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
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ActivityMainBinding
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.tools.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities.Companion.getTypedSerializable
import com.hifnawy.quran.ui.fragments.ChaptersList
import com.hifnawy.quran.ui.fragments.MediaPlayback
import com.hifnawy.quran.ui.fragments.RecitersList
import com.hifnawy.quran.ui.widgets.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    var reciters: List<Reciter> = mutableListOf()
    var chapters: List<Chapter> = mutableListOf()

    lateinit var binding: ActivityMainBinding

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

        val fragment = let {
            getIntentFragment() ?: MediaService.instance?.run {
                if (isMediaPlaying) SharedPreferencesManager(this).lastReciter?.run { ChaptersList(this) }
                    ?: RecitersList()
                else RecitersList()
            } ?: RecitersList()
        }

        fetchDataAndLaunchFragment(fragment)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        getIntentFragment()?.apply { fetchDataAndLaunchFragment(this) }
    }

    private fun getIntentFragment(): Fragment? {
        Log.d(this::class.simpleName, "Intent: $intent ${intent?.extras}")
        if (intent == null) return null
        if (!intent.hasCategory(NowPlaying::class.simpleName)) return null

        val fragment = let {
            val reciter = intent.getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
            val chapter = intent.getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)

            if ((reciter == null) || (chapter == null)) RecitersList() else MediaPlayback(
                reciter, chapter
            )
        }

        Log.d(this::class.simpleName, "fragment: $fragment")

        return fragment
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount == 0) {
            super.onBackPressed()
        } else {
            Log.d(
                this::class.simpleName,
                "showing ${supportFragmentManager.fragments.dropLast(1).last()}..."
            )
            supportFragmentManager.beginTransaction()
                .show(supportFragmentManager.fragments.dropLast(1).last()).commit()

            Log.d(
                this::class.simpleName, "popping ${supportFragmentManager.fragments.last()}..."
            )
            supportFragmentManager.popBackStackImmediate()
        }
    }

    private fun fetchDataAndLaunchFragment(fragment: Fragment) {
        lifecycleScope.launch(Dispatchers.IO) {
            reciters =
                lifecycleScope.async(context = Dispatchers.IO) { QuranAPI.getRecitersList() }.await()
            chapters =
                lifecycleScope.async(context = Dispatchers.IO) { QuranAPI.getChaptersList() }.await()

            // lifecycleScope.async(context = Dispatchers.IO) {
            //     updateChapterPaths(this@MainActivity, reciters, chapters)
            // }.await()
            //
            // Log.d(Utilities::class.simpleName, "SharedPrefs Updated!!!")

            withContext(Dispatchers.Main) {
                supportFragmentManager.beginTransaction().add(binding.fragmentContainer.id, fragment)
                    .commit()
            }
        }
    }
}