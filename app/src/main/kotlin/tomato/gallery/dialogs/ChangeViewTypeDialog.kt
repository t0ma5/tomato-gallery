package tomato.gallery.dialogs

import android.widget.RadioGroup
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.beVisibleIf
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.helpers.VIEW_TYPE_GRID
import com.simplemobiletools.commons.helpers.VIEW_TYPE_LIST
import tomato.gallery.databinding.DialogChangeViewTypeBinding
import tomato.gallery.extensions.config
import tomato.gallery.helpers.SHOW_ALL

class ChangeViewTypeDialog(val activity: BaseSimpleActivity, val fromFoldersView: Boolean, val path: String = "", val callback: () -> Unit) {
    private val binding = DialogChangeViewTypeBinding.inflate(activity.layoutInflater)
    private var config = activity.config
    private var pathToUse = if (path.isEmpty()) SHOW_ALL else path

    init {
        binding.apply {
            changeViewTypeDialogRadioTree.beVisibleIf(fromFoldersView)

            val viewToCheck = if (fromFoldersView) {
                when {
                    config.treeModeEnabled -> changeViewTypeDialogRadioTree.id
                    config.viewTypeFolders == VIEW_TYPE_GRID -> changeViewTypeDialogRadioGrid.id
                    else -> changeViewTypeDialogRadioList.id
                }
            } else {
                val currViewType = config.getFolderViewType(pathToUse)
                if (currViewType == VIEW_TYPE_GRID) {
                    changeViewTypeDialogRadioGrid.id
                } else {
                    changeViewTypeDialogRadioList.id
                }
            }

            changeViewTypeDialogRadio.check(viewToCheck)
            changeViewTypeDialogGroupDirectSubfolders.isChecked = config.groupDirectSubfolders
            updateFolderOnlyOptions()
            changeViewTypeDialogRadio.setOnCheckedChangeListener { _: RadioGroup, _ ->
                updateFolderOnlyOptions()
            }

            changeViewTypeDialogUseForThisFolder.apply {
                beVisibleIf(!fromFoldersView)
                isChecked = config.hasCustomViewType(pathToUse)
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.simplemobiletools.commons.R.string.ok) { dialog, which -> dialogConfirmed() }
            .setNegativeButton(com.simplemobiletools.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this)
            }
    }

    private fun updateFolderOnlyOptions() {
        val treeSelected = binding.changeViewTypeDialogRadio.checkedRadioButtonId == binding.changeViewTypeDialogRadioTree.id
        binding.changeViewTypeDialogGroupDirectSubfolders.beVisibleIf(fromFoldersView && !treeSelected)
        binding.groupDirectSubfoldersDivider.root.beVisibleIf(fromFoldersView && !treeSelected)
    }

    private fun dialogConfirmed() {
        val checkedId = binding.changeViewTypeDialogRadio.checkedRadioButtonId
        if (fromFoldersView) {
            when (checkedId) {
                binding.changeViewTypeDialogRadioTree.id -> {
                    if (!config.treeModeEnabled) {
                        config.viewTypeFoldersBeforeTree = config.viewTypeFolders
                    }
                    config.treeModeEnabled = true
                    config.viewTypeFolders = VIEW_TYPE_LIST
                }
                binding.changeViewTypeDialogRadioGrid.id -> {
                    config.treeModeEnabled = false
                    config.viewTypeFoldersBeforeTree = 0
                    config.viewTypeFolders = VIEW_TYPE_GRID
                }
                else -> {
                    config.treeModeEnabled = false
                    config.viewTypeFoldersBeforeTree = 0
                    config.viewTypeFolders = VIEW_TYPE_LIST
                }
            }
            if (binding.changeViewTypeDialogGroupDirectSubfolders.isShown) {
                config.groupDirectSubfolders = binding.changeViewTypeDialogGroupDirectSubfolders.isChecked
            }
        } else {
            val viewType = if (checkedId == binding.changeViewTypeDialogRadioGrid.id) {
                VIEW_TYPE_GRID
            } else {
                VIEW_TYPE_LIST
            }
            if (binding.changeViewTypeDialogUseForThisFolder.isChecked) {
                config.saveFolderViewType(pathToUse, viewType)
            } else {
                config.removeFolderViewType(pathToUse)
                config.viewTypeFiles = viewType
            }
        }

        callback()
    }
}
