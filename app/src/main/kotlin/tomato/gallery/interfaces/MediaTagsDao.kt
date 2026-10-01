package tomato.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tomato.gallery.models.MediaTag

@Dao
interface MediaTagsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(tag: MediaTag): Long

    @Query("SELECT DISTINCT tag FROM media_tags ORDER BY tag COLLATE NOCASE")
    fun getAllTags(): List<String>

    @Query("SELECT tag FROM media_tags WHERE full_path = :path COLLATE NOCASE ORDER BY tag COLLATE NOCASE")
    fun getTagsForPath(path: String): List<String>

    @Query("SELECT DISTINCT full_path FROM media_tags WHERE tag = :tag COLLATE NOCASE")
    fun getPathsWithTag(tag: String): List<String>

    @Query("SELECT DISTINCT full_path FROM media_tags WHERE tag LIKE '%' || :query || '%' COLLATE NOCASE")
    fun getPathsMatchingTag(query: String): List<String>

    @Query("DELETE FROM media_tags WHERE full_path = :path COLLATE NOCASE AND tag = :tag COLLATE NOCASE")
    fun deleteTag(path: String, tag: String)

    @Query("DELETE FROM media_tags WHERE full_path = :path COLLATE NOCASE")
    fun deleteTagsForPath(path: String)

    @Query("UPDATE media_tags SET full_path = :newPath WHERE full_path = :oldPath COLLATE NOCASE")
    fun updatePath(oldPath: String, newPath: String)
}
