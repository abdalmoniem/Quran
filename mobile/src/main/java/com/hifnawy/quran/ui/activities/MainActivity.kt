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
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.hifnawy.quran.BuildConfig
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ActivityMainBinding
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import com.hifnawy.quran.ui.fragments.MediaPlayback
import com.hifnawy.quran.ui.fragments.RecitersList
import com.hifnawy.quran.ui.widgets.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.fixedRateTimer

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private val sharedPrefsManager: SharedPreferencesManager by lazy { SharedPreferencesManager(this) }

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
            setIcon(R.drawable.circular_app_icon)
            // providing title for the ActionBar
            title = "   ${getString(R.string.quran)}"
            // providing subtitle for the ActionBar
            subtitle = "   ${getString(R.string.reciters)}"
        }

        Firebase.crashlytics.setCrashlyticsCollectionEnabled(BuildConfig.DEBUG)
        val fragment = getIntentFragment(intent) ?: RecitersList()
        launchFragment(fragment)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        getIntentFragment(intent)?.apply { launchFragment(this) }
    }

    private fun getIntentFragment(intent: Intent?): Fragment? {
        if (intent == null) return null
        if (!intent.hasCategory(NowPlaying::class.simpleName)) return null
        val fragment = let {
            val reciter = intent.getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
            val chapter = intent.getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)
            val chapterPosition = intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, 0L)

            Log.d(
                    this::class.simpleName,
                    "Reciter: $reciter\nChapter: $chapter\n chapterPosition: $chapterPosition"
            )

            if ((reciter == null) || (chapter == null)) RecitersList() else MediaPlayback(
                    reciter, chapter, chapterPosition
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
                        // Open the specific App Info page:
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    } catch (e: ActivityNotFoundException) {
                        // Open the generic Apps page:
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
        if (supportFragmentManager.fragments.isEmpty()) return super.onBackPressed()
        if (supportFragmentManager.backStackEntryCount == 0) return super.onBackPressed()

        Log.d(
                this::class.simpleName,
                "showing ${supportFragmentManager.fragments.dropLast(1).last()}..."
        )
        supportFragmentManager.beginTransaction()
            .show(supportFragmentManager.fragments.dropLast(1).last()).commit()

        Log.d(this::class.simpleName, "popping ${supportFragmentManager.fragments.last()}...")
        supportFragmentManager.popBackStackImmediate()

    }

    private fun launchFragment(fragment: Fragment) {
        lifecycleScope.launch {
            lifecycleScope.async(context = Dispatchers.IO) { checkDataConsistency() }.await()
            withContext(Dispatchers.Main) {
                supportFragmentManager.beginTransaction().add(binding.fragmentContainer.id, fragment)
                    .commit()
            }
        }
    }

    private suspend fun checkDataConsistency() {
        if (sharedPrefsManager.areChapterPathsSaved) return

        withContext(Dispatchers.Main) {
            val (dialog, dialogBinding) = DialogBuilder.prepareUpdateDialog(binding.root.context)
            val quotes = resources.getStringArray(R.array.quotes)
            val periodicity = 50L
            val timeMs = 10000L
            val counterThreshold = timeMs / periodicity
            var counter = 0L

            dialog.show()
            dialogBinding.quote.text = quotes.random()

            fixedRateTimer("quotes", true, 0, periodicity) {
                counter++

                runOnUiThread {
                    dialogBinding.linearProgressBar.progress =
                        ((counter.toFloat() / counterThreshold.toFloat()) * 100f).toInt()
                }

                if (counter % counterThreshold == 0L) {
                    runOnUiThread {
                        dialogBinding.quote.text = quotes.random()

                        Log.d(MainActivity::class.simpleName, dialogBinding.quote.text.toString())
                    }
                    counter = 0L
                }

                if (sharedPrefsManager.areChapterPathsSaved) cancel()
            }

            withContext(Dispatchers.IO) { Utilities.updateChapterPaths(this@MainActivity) }

            dialog.dismiss()
        }

        sharedPrefsManager.areChapterPathsSaved = true
    }
}