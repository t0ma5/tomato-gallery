package tomato.gallery.helpers

object TagInput {
    fun split(raw: String): List<String> {
        return raw.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    fun expand(stored: List<String>): List<String> {
        return stored.flatMap { split(it) }.distinctBy { it.lowercase() }
    }
}
