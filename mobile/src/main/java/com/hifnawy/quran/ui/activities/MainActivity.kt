package com.hifnawy.quran.ui.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.hifnawy.quran.BuildConfig
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ActivityMainBinding
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import com.hifnawy.quran.ui.fragments.MediaPlaybackDirections
import com.hifnawy.quran.ui.fragments.RecitersListDirections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.fixedRateTimer

private val TAG = MainActivity::class.simpleName

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private val sharedPrefsManager: SharedPreferencesManager by lazy { SharedPreferencesManager(this) }
    lateinit var navController: NavController
    lateinit var mediaPlaybackNavController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        setSupportActionBar(binding.appToolbar)

        navController =
                (supportFragmentManager.findFragmentById(binding.fragmentContainer.id) as NavHostFragment).navController
        mediaPlaybackNavController =
                (supportFragmentManager.findFragmentById(binding.mediaPlaybackFragmentContainer.id) as NavHostFragment).navController

        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) &&
            (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 93)
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

        Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        lifecycleScope.launch {
            lifecycleScope.async(context = Dispatchers.IO) { checkDataConsistency() }.await()
        }

        checkIntent(intent)
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

    override fun onSupportNavigateUp(): Boolean {
        val stackPopped = navController.navigateUp()

        if (!stackPopped) finish()

        return stackPopped || super.onSupportNavigateUp()
    }

    private fun checkIntent(intent: Intent?) {
        if ((intent != null) &&
            (intent.hasCategory(Constants.NowPlayingClass.simpleName) ||
             intent.hasCategory(Constants.MAIN_ACTIVITY_INTENT_CATEGORY))
        ) {
            val reciter = intent.getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
            val chapter = intent.getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)
            val chapterPosition = intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, 0L)

            if ((reciter != null) && (chapter != null)) showMediaPlayer(
                    reciter,
                    chapter,
                    chapterPosition
            )
        } else if (MediaService.isMediaPlaying) {
            showMediaPlayer()
        } else {
            // ??????
        }
    }

    private fun showMediaPlayer(
            reciter: Reciter? = null,
            chapter: Chapter? = null,
            chapterPosition: Long? = null
    ) {
        binding.mediaPlaybackFragmentContainer.visibility = View.VISIBLE
        navController.navigate(
                RecitersListDirections.toChaptersList(
                        reciter ?: sharedPrefsManager.lastReciter!!
                )
        )
        mediaPlaybackNavController.navigate(
                MediaPlaybackDirections.toMediaPlayback(
                        reciter ?: sharedPrefsManager.lastReciter!!,
                        chapter ?: sharedPrefsManager.lastChapter!!,
                        chapterPosition ?: sharedPrefsManager.lastChapterPosition
                )
        )
    }

    private suspend fun checkDataConsistency() {
        if (sharedPrefsManager.areChapterPathsSaved && sharedPrefsManager.areChapterPathsRenamed) return

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

                if (sharedPrefsManager.areChapterPathsSaved && sharedPrefsManager.areChapterPathsRenamed) {
                    cancel()
                    Log.d(TAG, "finished")
                    dialog.dismiss()
                }
            }

            withContext(Dispatchers.IO) { updateChapterPaths(this@MainActivity) }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun updateChapterPaths(context: Context) {
        val reciters = QuranAPI.getRecitersList(this@MainActivity)
        val chapters = QuranAPI.getChaptersList(this@MainActivity)
        Log.d(TAG, "Checking Chapters' Paths...")
        val filesDir = filesDir.listFiles()
        if (!sharedPrefsManager.areChapterPathsRenamed && filesDir != null) {
            for (file in filesDir) {
                if ("`" in file.name) {
                    val reciter = reciters.find { reciter: Reciter -> reciter.reciter_name == file.name }
                                  ?: continue

                    for (chapter in chapters) {
                        val chapterFilePath =
                                sharedPrefsManager.getChapterPath(reciter, chapter) ?: continue

                        Log.d(TAG, "renaming $chapterFilePath to ${chapterFilePath.replace("`", "'")}")
                        sharedPrefsManager.setChapterPath(reciter, chapter)
                    }
                    val newFileName = file.absolutePath.replace("`", "'")

                    Log.d(TAG, "renaming ${file.absolutePath} to $newFileName")
                    file.renameTo(File(newFileName))
                }
            }

            sharedPrefsManager.areChapterPathsRenamed = true
        }

        if (!sharedPrefsManager.areChapterPathsSaved) {
            for (reciter in reciters) {
                for (chapter in chapters) {
                    val chapterFile = Constants.getChapterFile(context, reciter, chapter)

                    if (!chapterFile.exists()) {
                        Log.d(
                                TAG,
                                "${chapterFile.absolutePath} doesn't exist, skipping"
                        )
                        continue
                    }

                    Log.d(TAG, "${chapterFile.absolutePath} exists, checking...")
                    val chapterAudioFileUrl =
                            QuranAPI.getChapterAudioFile(reciter.id, chapter.id)?.audio_url ?: continue

                    (URL(chapterAudioFileUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept-Encoding", "identity")
                        connect()

                        if (responseCode !in 200..299) {
                            return@apply
                        }
                        val chapterFileSize = Files.readAttributes(
                                chapterFile.toPath(), BasicFileAttributes::class.java
                        ).size()

                        if (chapterFileSize != contentLength.toLong()) {
                            Log.d(
                                    TAG,
                                    "Chapter Audio File incomplete, Deleting chapterPath:\nreciter #${reciter.id}: ${reciter.reciter_name}\nchapter: ${chapter.name_simple}\npath: ${chapterFile.absolutePath}\n"
                            )
                            chapterFile.delete()
                        } else {
                            Log.d(
                                    TAG,
                                    "Chapter Audio File complete, Updating chapterPath:\nreciter #${reciter.id}: ${reciter.reciter_name}\nchapter: ${chapter.name_simple}\npath: ${chapterFile.absolutePath}\nsize: ${chapterFileSize / 1024 / 1024} MBs"
                            )
                            SharedPreferencesManager(context).setChapterPath(
                                    reciter, chapter
                            )
                        }
                    }
                }
            }

            sharedPrefsManager.areChapterPathsSaved = true
        }

        Log.d(TAG, "SharedPrefs Updated!!!")
    }
}