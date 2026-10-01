package tomato.gallery.dialogs

import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.emoji2.emojipicker.EmojiPickerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import tomato.gallery.R

class AddStickerDialog(val activity: BaseSimpleActivity, val callback: (emoji: String) -> Unit) {
    companion object {
        private const val COLUMNS = 6
        private val STICKERS = listOf(
            "⚠️", "✅", "📍", "➡️", "❌", "⭕",
            "🌝", "🌞", "🌙", "☀️", "⭐", "✨",
            "😀", "😂", "😍", "😎", "😭", "👉",
            "🥰", "😡", "🤔", "😱", "😴", "🥳",
            "❤️", "🔥", "👍", "❗", "❓", "💯",
            "🎉", "🌈", "📷", "❄️", "⚡", "💧"
        )
    }

    init {
        var dialog: AlertDialog? = null
        val cellSize = (activity.resources.displayMetrics.widthPixels / COLUMNS).coerceAtLeast(1)
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        STICKERS.chunked(COLUMNS).forEach { rowEmojis ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowEmojis.forEach { emoji ->
                val cell = TextView(activity).apply {
                    text = emoji
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    setPadding(4, 16, 4, 16)
                    setOnClickListener {
                        callback(emoji)
                        dialog?.dismiss()
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(cellSize, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            rows.addView(row)
        }

        val scroller = ScrollView(activity).apply {
            isFillViewport = true
            addView(
                rows,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(com.simplemobiletools.commons.R.string.cancel, null)
            .setNeutralButton(R.string.more_emojis, null)
            .apply {
                activity.setupDialogStuff(scroller, this, R.string.add_sticker) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        alertDialog.dismiss()
                        showSystemEmojiPicker { emoji ->
                            callback(emoji)
                        }
                    }
                }
            }
    }

    private fun showSystemEmojiPicker(onPicked: (String) -> Unit) {
        try {
            val sheet = BottomSheetDialog(activity, R.style.CustomBottomSheetDialogTheme)
            val height = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt().coerceAtLeast(1)
            val picker = EmojiPickerView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                emojiGridColumns = 8
                setOnEmojiPickedListener { item ->
                    onPicked(item.emoji)
                    sheet.dismiss()
                }
            }
            val container = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                addView(picker)
            }
            sheet.setContentView(container)
            sheet.setOnShowListener {
                sheet.behavior.skipCollapsed = true
                sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            sheet.show()
        } catch (_: Exception) {
        }
    }
}
