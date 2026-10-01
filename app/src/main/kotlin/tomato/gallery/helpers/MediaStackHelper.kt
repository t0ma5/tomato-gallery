package tomato.gallery.helpers

import tomato.gallery.models.Medium

/**
 * Collapse RAW+JPEG pairs, burst shots, and edited copies into a single grid cover.
 * Inspired by ReFra photo stacks (Apache-2.0).
 */
object MediaStackHelper {
    private val EDITED_SUFFIX = Regex("""(?i)([_ -](edited|edit|edytuj|bearbeitet)|~2)$""")
    private val BURST_TOKEN = Regex("""(?i)(_BURST\d{14}|_burst\d+)""")

    fun stack(media: ArrayList<Medium>): ArrayList<Medium> {
        if (media.size < 2) {
            return media
        }

        val groups = LinkedHashMap<String, ArrayList<Medium>>()
        media.forEach { medium ->
            val key = stackKey(medium)
            groups.getOrPut(key) { ArrayList() }.add(medium)
        }

        val result = ArrayList<Medium>(groups.size)
        for (group in groups.values) {
            if (group.size == 1 || !shouldCollapse(group)) {
                result.addAll(group)
                continue
            }

            val cover = pickCover(group)
            cover.stackMembers = ArrayList(group.map { it.path })
            result.add(cover)
        }
        return result
    }

    private fun shouldCollapse(group: List<Medium>): Boolean {
        val hasRaw = group.any { it.isRaw() }
        val hasImage = group.any { it.isImage() && !it.isRaw() }
        if (hasRaw && hasImage) {
            return true
        }
        if (group.any { BURST_TOKEN.containsMatchIn(it.name) }) {
            return true
        }
        if (group.any { EDITED_SUFFIX.containsMatchIn(it.name.substringBeforeLast('.', it.name)) }) {
            return true
        }
        return false
    }

    private fun stackKey(medium: Medium): String {
        val parent = medium.parentPath.trimEnd('/').lowercase()
        val stem = medium.name.substringBeforeLast('.', medium.name)
        val burstMatch = BURST_TOKEN.find(stem)
        if (burstMatch != null) {
            val prefix = stem.substring(0, burstMatch.range.first)
            if (prefix.isNotEmpty()) {
                return "$parent|burst:${prefix.lowercase()}"
            }
        }

        val base = EDITED_SUFFIX.replace(stem, "")
        return "$parent|${base.lowercase()}"
    }

    private fun pickCover(group: List<Medium>): Medium {
        val ranked = group.sortedWith(
            compareBy<Medium> { coverRank(it) }
                .thenByDescending { it.taken }
                .thenByDescending { it.size }
        )
        return ranked.first()
    }

    private fun coverRank(medium: Medium): Int {
        return when {
            medium.isPortrait() -> 0
            medium.isImage() && !medium.isRaw() -> 1
            medium.isGIF() -> 2
            medium.isVideo() -> 3
            medium.isRaw() -> 4
            else -> 5
        }
    }
}
