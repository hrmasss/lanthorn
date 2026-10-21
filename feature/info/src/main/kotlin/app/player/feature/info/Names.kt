package app.player.feature.info

/** Plain names for what FFmpeg and mpv report, as people know them. */
internal object Names {
    /** mpv's `file-format` is FFmpeg's demuxer list ("mov,mp4,m4a,3gp,3g2,mj2"); the extension decides. */
    fun container(format: String?, fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val byExt = mapOf(
            "mp4" to "MP4", "m4v" to "MP4", "mov" to "MOV", "3gp" to "3GP", "mkv" to "MKV", "webm" to "WebM",
            "avi" to "AVI", "ts" to "MPEG-TS", "m2ts" to "MPEG-TS", "mts" to "MPEG-TS", "flv" to "FLV",
            "wmv" to "WMV", "mpg" to "MPEG-PS", "mpeg" to "MPEG-PS", "vob" to "VOB", "ogv" to "Ogg",
        )
        byExt[ext]?.let { return it }
        val f = format ?: return "-"
        return when {
            f.startsWith("mov,mp4") -> "MP4"
            f.startsWith("matroska") -> "MKV"
            f == "mpegts" -> "MPEG-TS"
            f == "avi" -> "AVI"
            f == "flv" -> "FLV"
            else -> f.substringBefore(',').uppercase()
        }
    }

    fun codec(name: String?): String = when (name) {
        null, "" -> "-"
        "h264" -> "H.264"
        "hevc" -> "HEVC (H.265)"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "vp8" -> "VP8"
        "mpeg4" -> "MPEG-4"
        "mpeg2video" -> "MPEG-2"
        "mpeg1video" -> "MPEG-1"
        "vc1" -> "VC-1"
        "aac" -> "AAC"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "dts" -> "DTS"
        "truehd" -> "Dolby TrueHD"
        "mp3", "mp3float" -> "MP3"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "flac" -> "FLAC"
        "alac" -> "ALAC"
        "subrip" -> "SRT"
        "ass", "ssa" -> "ASS"
        "mov_text" -> "MP4 text"
        "webvtt" -> "WebVTT"
        "hdmv_pgs_subtitle" -> "PGS"
        "dvd_subtitle" -> "VobSub"
        else -> name.uppercase()
    }

    fun channels(n: Long?): String = when (n) {
        null -> "-"
        1L -> "Mono"
        2L -> "Stereo"
        3L -> "2.1"
        6L -> "5.1"
        8L -> "7.1"
        else -> "$n channels"
    }
}
