package tomato.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.beGone
import com.simplemobiletools.commons.extensions.beVisible
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.showKeyboard
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.commons.extensions.value
import tomato.gallery.R
import tomato.gallery.databinding.DialogAddTagBinding
import tomato.gallery.helpers.TagInput

class AddTagDialog(
    val activity: BaseSimpleActivity,
    existing: List<String> = emptyList(),
    val onRemove: (String) -> Unit = {},
    val callback: (String) -> Unit
) {
    init {
        val binding = DialogAddTagBinding.inflate(activity.layoutInflater)
        val remaining = TagInput.expand(existing).toMutableList()

        fun refreshChips() {
            if (remaining.isEmpty()) {
                binding.addTagExisting.beGone()
                binding.addTagChips.beGone()
                binding.addTagChips.removeAllViews()
                return
            }
            binding.addTagExisting.text = activity.getString(R.string.current_tags)
            binding.addTagExisting.beVisible()
            binding.addTagChips.beVisible()
            binding.addTagChips.removeAllViews()
            remaining.forEach { tag ->
                val chip = Chip(activity).apply {
                    text = tag
                    isCheckable = false
                    isClickable = false
                    isCloseIconVisible = true
                    contentDescription = activity.getString(R.string.remove_tag_hint)
                    setOnCloseIconClickListener {
                        onRemove(tag)
                        remaining.removeAll { it.equals(tag, true) }
                        refreshChips()
                    }
                }
                binding.addTagChips.addView(chip)
            }
        }
        refreshChips()

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.simplemobiletools.commons.R.string.ok, null)
            .setNegativeButton(com.simplemobiletools.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.add_tag) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val tags = TagInput.split(binding.addTagValue.value)
                        if (tags.isEmpty()) {
                            dialog.dismiss()
                            return@setOnClickListener
                        }
                        val newTags = tags.filterNot { candidate ->
                            remaining.any { it.equals(candidate, true) }
                        }
                        if (newTags.isEmpty()) {
                            activity.toast(R.string.tag_exists)
                            return@setOnClickListener
                        }
                        callback(newTags.joinToString(", "))
                        dialog.dismiss()
                    }
                    activity.showKeyboard(binding.addTagValue)
                }
            }
    }
}
