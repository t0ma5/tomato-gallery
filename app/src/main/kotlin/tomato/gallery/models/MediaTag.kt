package tomato.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "media_tags", indices = [Index(value = ["full_path", "tag"], unique = true)])
data class MediaTag(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "full_path") var fullPath: String,
    @ColumnInfo(name = "tag") var tag: String
)
