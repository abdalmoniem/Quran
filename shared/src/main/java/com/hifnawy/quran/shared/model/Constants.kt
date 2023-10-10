package com.hifnawy.quran.shared.model

class Constants {
    companion object {

        const val MAIN_ACTIVITY_INTENT_CATEGORY = "com.hifnawy.quran.ui.activities.MainActivity"
        val NowPlayingClass: Class<*> = Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
        val MainActivityClass: Class<*> = Class.forName(MAIN_ACTIVITY_INTENT_CATEGORY)
    }

    enum class DownloadType {
        SINGLE_FILE, BULK
    }

    enum class ServiceUpdates {
        SERVICE_UPDATE, ERROR
    }

    enum class Actions {
        PLAY_MEDIA, PAUSE_MEDIA, TOGGLE_MEDIA, STOP_MEDIA, SKIP_TO_NEXT_MEDIA, SKIP_TO_PREVIOUS_MEDIA, SEEK_MEDIA
    }

    enum class IntentDataKeys {
        RECITER, CHAPTER, CHAPTER_URL, CHAPTER_DURATION, CHAPTER_POSITION, IS_MEDIA_PLAYING, SINGLE_DOWNLOAD_TYPE
    }
}