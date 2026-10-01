package tomato.simple.gallery.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.toast
import tomato.simple.gallery.R
import tomato.simple.gallery.databinding.DialogCopyTextBinding

class CopyTextDialog(private val activity: BaseSimpleActivity, private val text: String) {
    init {
        val binding = DialogCopyTextBinding.inflate(activity.layoutInflater)
        binding.copyTextValue.text = text

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.copy, null)
            .setNegativeButton(com.simplemobiletools.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.copy_text) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val clipboard = activity.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
                        activity.toast(R.string.copied_to_clipboard)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
