package tomato.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.showKeyboard
import com.simplemobiletools.commons.extensions.value
import tomato.gallery.R
import tomato.gallery.databinding.DialogAddTextBinding

class AddTextDialog(val activity: BaseSimpleActivity, val initialText: String = "", val callback: (text: String) -> Unit) {
    init {
        val binding = DialogAddTextBinding.inflate(activity.layoutInflater)
        if (initialText.isNotEmpty()) {
            binding.addTextValue.setText(initialText)
            binding.addTextValue.setSelection(initialText.length)
        }
        val titleRes = if (initialText.isEmpty()) R.string.add_text else com.simplemobiletools.commons.R.string.edit

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.simplemobiletools.commons.R.string.ok, null)
            .setNegativeButton(com.simplemobiletools.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleRes) { alertDialog ->
                    activity.showKeyboard(binding.addTextValue)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = binding.addTextValue.value.trim()
                        if (text.isEmpty()) {
                            return@setOnClickListener
                        }
                        callback(text)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
