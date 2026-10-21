package app.player.feature.subtitles

/**
 * Which files next to a video are its subtitles (US-39.1) and which track turns on by itself
 * (US-39.2). Pure, so it is tested on the JVM.
 */
internal object SubtitleMatch {
    /** SRT, ASS/SSA, VTT, SUB/IDX (VobSub or MicroDVD), PGS (.sup), TTML (.ttml, .dfxp, .xml), SAMI, plain text. */
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "ttml", "dfxp", "xml", "smi", "sami", "txt")

    /** `movie.mkv` matches `movie.srt`, `movie.en.srt`, `Movie.forced.eng.ass`; never `movie2.srt`. */
    fun matches(videoName: String, fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONS) return false
        // VobSub: the .sub file is loaded through its .idx; a .sub with an .idx beside it is skipped by [pick].
        val base = videoName.substringBeforeLast('.').lowercase()
        val name = fileName.substringBeforeLast('.').lowercase()
        return name == base || (name.startsWith(base) && name[base.length] in SEPARATORS)
    }

    /** Files to load for a video, VobSub `.sub` dropped when its `.idx` is there, in name order. */
    fun pick(videoName: String, fileNames: List<String>): List<String> {
        val matched = fileNames.filter { matches(videoName, it) }
        val idx = matched.filter { it.endsWith(".idx", ignoreCase = true) }.map { it.dropLast(4).lowercase() }.toSet()
        return matched.filterNot { it.endsWith(".sub", ignoreCase = true) && it.dropLast(4).lowercase() in idx }.sortedBy { it.lowercase() }
    }

    /**
     * The language a file name carries after the video's name (`movie.en.srt`, `movie_fre.srt`),
     * as a lower-case 2 or 3 letter code, or null.
     */
    fun languageOf(videoName: String, fileName: String): String? {
        val base = videoName.substringBeforeLast('.')
        val rest = fileName.substringBeforeLast('.').drop(base.length).lowercase()
        return rest.split(*SEPARATORS.toCharArray()).filter { it.isNotEmpty() }
            .lastOrNull { it.length in 2..3 && it.all(Char::isLetter) && it !in NOT_LANGUAGES }
    }

    /**
     * US-39.2: the track that turns on: one in the preferred language, else the first external,
     * else the default-flagged embedded track, else none. [preferred] holds every code form
     * of the language ("fr", "fra", "fre").
     */
    fun autoTrack(tracks: List<Track>, preferred: Set<String>): Track? {
        val subs = tracks.filter { it.type == "sub" }
        if (preferred.isNotEmpty()) subs.firstOrNull { it.lang?.lowercase() in preferred }?.let { return it }
        return subs.firstOrNull { it.external } ?: subs.firstOrNull { it.default }
    }

    private const val SEPARATORS = ".-_ "
    private val NOT_LANGUAGES = setOf("sdh", "cc", "hi", "srt", "sub", "ass")
}
