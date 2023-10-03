package com.hifnawy.quran.shared.model

class Constants {
    enum class ServiceUpdates {
        SERVICE_UPDATE, ERROR
    }
    enum class Actions {
        PLAY_MEDIA, PAUSE_MEDIA, STOP_MEDIA, SKIP_TO_NEXT_MEDIA, SKIP_TO_PREVIOUS_MEDIA, SEEK_MEDIA
    }

    enum class IntentDataKeys {
        RECITER, CHAPTER, CHAPTER_DURATION, CHAPTER_POSITION
    }
}