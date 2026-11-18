package com.shelter.app.playback

import com.shelter.app.data.generate.GeneratedTrack
import com.shelter.app.data.generate.LocalAudioFormats

internal fun PlayableTrack.toGeneratedTrack() = GeneratedTrack(
    name = title,
    artist = artist,
    artworkUrl = artworkUrl,
    album = album,
    // Local songs: an imported file keeps its path so any playlist it
    // is added to plays the copy on disk instead of a YouTube search.
    url = LocalAudioFormats.importedPathOrNull(playbackUrl)
        ?: videoId?.let { "https://music.youtube.com/watch?v=$it" }.orEmpty(),
)
