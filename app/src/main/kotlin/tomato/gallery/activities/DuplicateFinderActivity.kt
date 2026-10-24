package tomato.gallery.activities

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.NavigationIcon
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.models.RadioItem
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
    private var mediaTask: GetMediaAsynctask? = null

    @Volatile
    private var scanCancelled = false

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
        val task = GetMediaAsynctask(this, if (showAll) SHOW_ALL else folder, isPickImage = false, isPickVideo = false, showAll = showAll) { media ->
            if (scanCancelled) {
                return@GetMediaAsynctask
            }
            ensureBackgroundThread {
                val files = media.filterIsInstance<Medium>().flatMap { medium ->
                    if (medium.stackMembers.size > 1) medium.stackMembers.map { it to 0L } else listOf(medium.path to medium.size)
                }
                val found = DuplicateHasher.group(files) { scanCancelled }
                if (scanCancelled) {
                    return@ensureBackgroundThread
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
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
        }
        mediaTask = task
        task.execute()
    }

    override fun onDestroy() {
        scanCancelled = true
        mediaTask?.stopFetching()
        mediaTask = null
        super.onDestroy()
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
            val kept = group.paths.first()
            (holder.itemView as TextView).text = (listOf(getString(R.string.duplicate_keep_label, kept)) +
                group.paths.drop(1).map { getString(R.string.duplicate_delete_label, it) }).joinToString("\n")
            holder.itemView.setOnClickListener {
                val items = ArrayList(group.paths.mapIndexed { index, path -> RadioItem(index, path) })
                RadioGroupDialog(this@DuplicateFinderActivity, items, 0, R.string.duplicate_choose_keep) { picked ->
                    val keepPath = group.paths.getOrNull(picked as Int) ?: return@RadioGroupDialog
                    val extras = ArrayList(group.paths.filter { it != keepPath })
                    ConfirmationDialog(
                        this@DuplicateFinderActivity,
                        getString(R.string.duplicate_keep_label, keepPath) + "\n\n" + getString(R.string.delete_duplicates),
                        R.string.delete_duplicates,
                        com.simplemobiletools.commons.R.string.ok,
                        com.simplemobiletools.commons.R.string.cancel
                    ) {
                        deleteExtras(extras)
                        val adapterPos = groups.indexOf(group)
                        if (adapterPos != -1) {
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
            deleteFiles(items) {
                ensureBackgroundThread {
                    paths.forEach { onPathDeleted(it) }
                }
            }
        }
    }
}
