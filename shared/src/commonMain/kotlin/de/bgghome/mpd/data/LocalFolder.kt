package de.bgghome.mpd.data

/** Ein Geraeteordner mit Aufnahmen (Android: BUCKET_DISPLAY_NAME). */
data class LocalFolder(val name: String, val count: Int, val preview: String?)
