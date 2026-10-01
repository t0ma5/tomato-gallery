package tomato.gallery.helpers

import android.graphics.Typeface
import tomato.gallery.R

object EditorFonts {
    data class Option(val id: Int, val nameRes: Int, val family: String, val style: Int = Typeface.NORMAL)

    val all = listOf(
        Option(0, R.string.font_sans, "sans-serif"),
        Option(1, R.string.font_serif, "serif"),
        Option(2, R.string.font_monospace, "monospace"),
        Option(3, R.string.font_bold, "sans-serif", Typeface.BOLD),
        Option(4, R.string.font_medium, "sans-serif-medium"),
        Option(5, R.string.font_light, "sans-serif-light"),
        Option(6, R.string.font_condensed, "sans-serif-condensed"),
        Option(7, R.string.font_black, "sans-serif-black"),
        Option(8, R.string.font_cursive, "cursive"),
        Option(9, R.string.font_casual, "casual")
    )

    fun typeface(id: Int): Typeface {
        val option = all.find { it.id == id } ?: all.first()
        return Typeface.create(option.family, option.style)
    }
}
