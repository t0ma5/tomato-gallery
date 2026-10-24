package tomato.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tomato.gallery.models.DateTaken

@Dao
interface DateTakensDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(dateTakens: List<DateTaken>)

    @Query("SELECT full_path, filename, parent_path, date_taken, last_fixed, last_modified FROM date_takens WHERE parent_path = :path COLLATE NOCASE")
    fun getDateTakensFromPath(path: String): List<DateTaken>

    @Query("SELECT full_path, filename, parent_path, date_taken, last_fixed, last_modified FROM date_takens")
    fun getAllDateTakens(): List<DateTaken>

    @Query("UPDATE OR REPLACE date_takens SET full_path = :newPath, filename = :newFilename, parent_path = :newParentPath WHERE full_path = :oldPath COLLATE NOCASE")
    fun updatePath(oldPath: String, newPath: String, newFilename: String, newParentPath: String): Int

    @Query("UPDATE OR REPLACE date_takens SET full_path = :newPrefix || substr(full_path, length(:oldPrefix) + 1), parent_path = substr(:newPrefix, 1, length(:newPrefix) - 1) || substr(parent_path, length(:oldPrefix)) WHERE substr(full_path, 1, length(:oldPrefix)) = :oldPrefix COLLATE NOCASE")
    fun updateFolderPrefix(oldPrefix: String, newPrefix: String): Int

    @Query("DELETE FROM date_takens WHERE full_path = :path COLLATE NOCASE")
    fun deletePath(path: String)
}
