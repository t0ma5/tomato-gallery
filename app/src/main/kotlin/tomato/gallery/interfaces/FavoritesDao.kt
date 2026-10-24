package tomato.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tomato.gallery.models.Favorite

@Dao
interface FavoritesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(favorite: Favorite)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(favorites: List<Favorite>)

    @Query("SELECT favorites.full_path FROM favorites INNER JOIN media ON favorites.full_path = media.full_path WHERE media.deleted_ts = 0")
    fun getValidFavoritePaths(): List<String>

    @Query("SELECT id FROM favorites WHERE full_path = :path COLLATE NOCASE")
    fun isFavorite(path: String): Boolean

    @Query("UPDATE OR REPLACE favorites SET filename = :newFilename, full_path = :newFullPath, parent_path = :newParentPath WHERE full_path = :oldPath COLLATE NOCASE")
    fun updateFavorite(newFilename: String, newFullPath: String, newParentPath: String, oldPath: String)

    @Query("UPDATE OR REPLACE favorites SET full_path = :newPrefix || substr(full_path, length(:oldPrefix) + 1), parent_path = substr(:newPrefix, 1, length(:newPrefix) - 1) || substr(parent_path, length(:oldPrefix)) WHERE substr(full_path, 1, length(:oldPrefix)) = :oldPrefix COLLATE NOCASE")
    fun updateFolderPrefix(oldPrefix: String, newPrefix: String): Int

    @Query("DELETE FROM favorites WHERE full_path = :path COLLATE NOCASE")
    fun deleteFavoritePath(path: String)

    @Query("DELETE FROM favorites")
    fun clearFavorites()
}
