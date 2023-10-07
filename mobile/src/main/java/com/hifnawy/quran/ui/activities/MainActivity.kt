package com.hifnawy.quran.ui.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.ActivityMainBinding
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities
import com.hifnawy.quran.ui.dialogs.DialogBuilder
import com.hifnawy.quran.ui.fragments.ChaptersList
import com.hifnawy.quran.ui.fragments.MediaPlayback
import com.hifnawy.quran.ui.fragments.RecitersList
import com.hifnawy.quran.ui.widgets.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    lateinit var mediaService: MediaService
    private val sharedPrefsManager: SharedPreferencesManager by lazy { SharedPreferencesManager(this) }
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            synchronized(this@MainActivity) {
                mediaService = (iBinder as MediaService.ServiceBinder).instance
                val fragment = let {
                    getIntentFragment(intent) ?: mediaService.run {
                        if (isMediaPlaying) sharedPrefsManager.lastReciter?.run {
                            ChaptersList(
                                    this
                            )
                        } ?: RecitersList()
                        else RecitersList()
                    }
                }

                launchFragment(fragment)
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            synchronized(this@MainActivity) {
                // mediaService = null
            }
        }
    }

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

        startForegroundService(Intent(this, MediaService::class.java).apply {
            action = Constants.Actions.START_SERVICE.name
        })

        bindService(Intent(this, MediaService::class.java), serviceConnection, Context.BIND_IMPORTANT)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        getIntentFragment(intent)?.apply { launchFragment(this) }
    }

    private fun getIntentFragment(intent: Intent?): Fragment? {
        Log.d(this::class.simpleName, "Intent: $intent ${intent?.extras}")
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
        lateinit var dialog: AlertDialog

        withContext(Dispatchers.Main) {
            dialog = DialogBuilder.prepareUpdateDialog(binding.root.context)
            dialog.show()
        }
        Utilities.updateChapterPaths(this)
        withContext(Dispatchers.Main) { dialog.dismiss() }

        sharedPrefsManager.areChapterPathsSaved = true
    }
}