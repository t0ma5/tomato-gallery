package tomato.gallery.helpers

import android.content.Context
import com.zomato.photofilters.SampleFilters
import com.zomato.photofilters.imageprocessors.Filter

data class NamedFilter(val name: String, val filter: Filter)

/**
 * Zomato's JitPack artifact ships SampleFilters, not the androidhive FilterPack class,
 * and Filter itself has no name field. Keep names on our side.
 */
object FilterPack {
    fun getFilterPack(@Suppress("UNUSED_PARAMETER") context: Context): List<NamedFilter> {
        return listOf(
            NamedFilter("StarLit", SampleFilters.getStarLitFilter()),
            NamedFilter("Blue Mess", SampleFilters.getBlueMessFilter()),
            NamedFilter("Struck", SampleFilters.getAweStruckVibeFilter()),
            NamedFilter("Lime", SampleFilters.getLimeStutterFilter()),
            NamedFilter("Whisper", SampleFilters.getNightWhisperFilter()),
        )
    }
}
