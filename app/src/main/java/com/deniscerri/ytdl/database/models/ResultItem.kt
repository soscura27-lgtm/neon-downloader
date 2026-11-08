package com.deniscerri.ytdl.database.models

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Entity(tableName = "results")
@Parcelize
data class ResultItem(
    @PrimaryKey(autoGenerate = true)
    var id: Long,
    var url: String,
    var title: String,
    var author: String,
    val duration: String,
    val thumb: String,
    val website: String,
    var playlistTitle: String,
    var formats: List<Format> = emptyList(),
    @ColumnInfo(defaultValue = "")
    var urls: String,
    var chapters: List<ChapterItem>?,
    @ColumnInfo(defaultValue = "")
    var playlistURL: String? = "",
    @ColumnInfo(defaultValue = "")
    var playlistIndex: Int? = null,
    var creationTime: Long = System.currentTimeMillis() / 1000,
    @ColumnInfo(defaultValue = "[]")
    var availableSubtitles: List<String> = listOf()
) : Parcelable {
    /** Visualizaciones (solo en memoria, para el selector masivo; -1 si no se conocen). */
    @Ignore
    @IgnoredOnParcel
    var viewCount: Long = -1L

    /** Fecha de subida aproximada en segundos desde 1970 (solo en memoria; -1 si no se conoce). */
    @Ignore
    @IgnoredOnParcel
    var uploadTimestamp: Long = -1L
}