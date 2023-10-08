package com.hifnawy.quran.shared.model

class Constants {
    companion object {

        val NowPlayingClass: Class<*> = Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
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