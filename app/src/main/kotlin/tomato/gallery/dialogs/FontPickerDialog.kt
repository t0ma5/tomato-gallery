package tomato.gallery.dialogs

import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.getProperPrimaryColor
import com.simplemobiletools.commons.extensions.getProperTextColor
import com.simplemobiletools.commons.extensions.setupDialogStuff
import tomato.gallery.R
import tomato.gallery.helpers.EditorFonts

class FontPickerDialog(
    val activity: BaseSimpleActivity,
    private val currentFontId: Int,
    val callback: (fontId: Int) -> Unit
) {
    init {
        var dialog: AlertDialog? = null
        val highlight = activity.getProperPrimaryColor()
        val textColor = activity.getProperTextColor()
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        EditorFonts.all.forEach { option ->
            val row = TextView(activity).apply {
                text = activity.getString(option.nameRes)
                typeface = EditorFonts.typeface(option.id)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(24, 28, 24, 28)
                setTextColor(if (option.id == currentFontId) highlight else textColor)
                setOnClickListener {
                    callback(option.id)
                    dialog?.dismiss()
                }
            }
            list.addView(row)
        }

        val scroller = ScrollView(activity).apply {
            addView(list)
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(com.simplemobiletools.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(scroller, this, R.string.font) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
