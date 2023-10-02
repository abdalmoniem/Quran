package com.hifnawy.quran.shared.model

class Constants {
    enum class Actions {
        PLAY_MEDIA, PAUSE_MEDIA, STOP_MEDIA, SKIP_TO_NEXT_MEDIA, SKIP_TO_PREVIOUS_MEDIA, SEEK_MEDIA, ERROR, SERVICE_UPDATE
    }

    enum class IntentDataKeys {
        RECITER, CHAPTER, CHAPTER_DURATION, CHAPTER_POSITION
    }
}