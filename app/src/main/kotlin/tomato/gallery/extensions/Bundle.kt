package tomato.gallery.extensions

import android.os.Build
import android.os.Bundle
import tomato.gallery.helpers.MEDIUM
import tomato.gallery.models.Medium

fun Bundle.mediumExtra(): Medium {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializable(MEDIUM, Medium::class.java)!!
    } else {
        @Suppress("DEPRECATION")
        getSerializable(MEDIUM) as Medium
    }
}
