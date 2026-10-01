package tomato.gallery.activities

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.NavigationIcon
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.models.FileDirItem
import tomato.gallery.R
import tomato.gallery.asynctasks.GetMediaAsynctask
import tomato.gallery.databinding.ActivityDuplicatesBinding
import tomato.gallery.extensions.*
import tomato.gallery.helpers.DIRECTORY
import tomato.gallery.helpers.DuplicateHasher
import tomato.gallery.helpers.SHOW_ALL
import tomato.gallery.models.Medium

class DuplicateFinderActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDuplicatesBinding::inflate)
    private val groups = ArrayList<DuplicateHasher.Group>()

    override fun onCreate(savedInstanceState: Bundle?) {
        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        updateMaterialActivityViews(binding.duplicatesCoordinator, binding.duplicatesList, useTransparentNavigation = true, useTopSearchMenu = false)
        setupMaterialScrollListener(binding.duplicatesList, binding.duplicatesToolbar)
        binding.duplicatesList.layoutManager = LinearLayoutManager(this)
        scan()
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.duplicatesToolbar, NavigationIcon.Arrow)
    }

    private fun scan() {
        val folder = intent.getStringExtra(DIRECTORY).orEmpty()
        val showAll = folder.isEmpty() || folder == SHOW_ALL
        GetMediaAsynctask(this, if (showAll) SHOW_ALL else folder, isPickImage = false, isPickVideo = false, showAll = showAll) { media ->
            ensureBackgroundThread {
                val files = media.filterIsInstance<Medium>().map { it.path to it.size }
                val found = DuplicateHasher.group(files)
                runOnUiThread {
                    groups.clear()
                    groups.addAll(found)
                    if (groups.isEmpty()) {
                        binding.duplicatesEmpty.text = getString(R.string.no_duplicates)
                        binding.duplicatesEmpty.beVisible()
                    } else {
                        binding.duplicatesEmpty.beGone()
                        binding.duplicatesList.adapter = GroupsAdapter()
                    }
                }
            }
        }.execute()
    }

    private inner class GroupsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = TextView(parent.context).apply {
                setPadding(48, 36, 48, 36)
                setTextColor(getProperTextColor())
                textSize = 16f
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun getItemCount() = groups.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val group = groups[position]
            val extras = ArrayList(group.paths.drop(1))
            (holder.itemView as TextView).text = getString(R.string.keep_one_delete_rest) + "\n" +
                group.paths.joinToString("\n") { it.substringAfterLast('/') }
            holder.itemView.setOnClickListener {
                ConfirmationDialog(this@DuplicateFinderActivity, "", R.string.delete_duplicates, com.simplemobiletools.commons.R.string.ok, com.simplemobiletools.commons.R.string.cancel) {
                    deleteExtras(extras)
                    val adapterPos = holder.bindingAdapterPosition
                    if (adapterPos != RecyclerView.NO_POSITION) {
                        groups.removeAt(adapterPos)
                        notifyItemRemoved(adapterPos)
                    }
                    if (groups.isEmpty()) {
                        binding.duplicatesEmpty.text = getString(R.string.no_duplicates)
                        binding.duplicatesEmpty.beVisible()
                    }
                }
            }
        }
    }

    private fun deleteExtras(paths: ArrayList<String>) {
        val items = ArrayList(paths.map { FileDirItem(it, it.substringAfterLast('/')) })
        if (shouldUseSystemTrash()) {
            trashPathsWithSystem(paths) { ok ->
                if (!ok) {
                    toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                }
            }
            return
        }
        if (config.useRecycleBin && !config.tempSkipRecycleBin) {
            movePathsInRecycleBin(paths) { ok ->
                if (ok) {
                    deleteFiles(items)
                } else {
                    toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            deleteFiles(items)
        }
    }
}
