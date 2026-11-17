package com.ranobe.audioreader.data.local

import androidx.room.TypeConverter
import com.ranobe.audioreader.data.local.entity.AudioStatus

class Converters {
    @TypeConverter
    fun audioStatusToString(value: AudioStatus): String = value.name

    @TypeConverter
    fun stringToAudioStatus(value: String): AudioStatus =
        runCatching { AudioStatus.valueOf(value) }.getOrDefault(AudioStatus.NONE)
}
