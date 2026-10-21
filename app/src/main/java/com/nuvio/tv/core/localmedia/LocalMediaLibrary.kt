package com.nuvio.tv.core.localmedia

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds videos on internal storage (e.g. `Movies/`) and on every mounted USB drive.
 *
 * MediaStore is the primary source because it already indexes removable volumes. Some TV
 * firmwares never index USB drives, so any mounted volume that MediaStore returned nothing for
 * is walked directly as a fallback.
 */
@Singleton
class LocalMediaLibrary @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private var cachedFiles: List<LocalVideoFile> = emptyList()
    private var cachedAtMs = 0L

    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED

    suspend fun findMatches(query: LocalMediaQuery, limit: Int = MAX_MATCHES): List<LocalMediaMatch> {
        if (!hasPermission()) return emptyList()
        val files = files()
        return withContext(Dispatchers.Default) {
            LocalMediaMatcher.rank(query, files).take(limit)
        }
    }

    /** True when [uri] still points at a readable file, e.g. the USB drive has not been unplugged. */
    suspend fun isAvailable(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "file") {
                File(requireNotNull(parsed.path)).canRead()
            } else {
                context.contentResolver.openFileDescriptor(parsed, "r")?.use { true } ?: false
            }
        }.getOrDefault(false)
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Returns true once, while the permission is missing and has never been requested. */
    fun consumePermissionPrompt(): Boolean {
        if (hasPermission() || prefs.getBoolean(KEY_PERMISSION_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        return true
    }

    fun invalidate() {
        cachedAtMs = 0L
    }

    private suspend fun files(): List<LocalVideoFile> = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs < CACHE_TTL_MS) return@withLock cachedFiles
        val scanned = withContext(Dispatchers.IO) {
            runCatching { scan() }
                .onFailure { Log.w(TAG, "Local media scan failed", it) }
                .getOrDefault(emptyList())
        }
        Log.d(TAG, "Local media scan found ${scanned.size} videos")
        cachedFiles = scanned
        cachedAtMs = now
        scanned
    }

    private fun scan(): List<LocalVideoFile> {
        val indexed = queryMediaStore()
        // Android 10 cannot tell which root a MediaStore row lives on, so walking would duplicate it.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && indexed.isNotEmpty()) return indexed.map { it.file }
        val indexedRoots = indexed.mapNotNullTo(HashSet()) { it.rootPath }
        val walked = storageRoots()
            .filter { root -> root.absolutePath !in indexedRoots }
            .flatMap { root -> walk(root) }
        return indexed.map { it.file } + walked
    }

    private class IndexedFile(val file: LocalVideoFile, val rootPath: String?)

    private fun queryMediaStore(): List<IndexedFile> {
        val results = mutableListOf<IndexedFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val storageManager = context.getSystemService(StorageManager::class.java)
            for (volume in MediaStore.getExternalVolumeNames(context)) {
                val collection = MediaStore.Video.Media.getContentUri(volume)
                val storageVolume = runCatching { storageManager?.getStorageVolume(collection) }.getOrNull()
                val label = storageVolume?.getDescription(context)
                val rootPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    storageVolume?.directory?.absolutePath
                } else {
                    null
                }
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION
                )
                runCatching {
                    context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameCol) ?: continue
                            results += IndexedFile(
                                file = LocalVideoFile(
                                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString(),
                                    relativePath = cursor.getString(pathCol).orEmpty(),
                                    fileName = name,
                                    sizeBytes = cursor.getLong(sizeCol).takeIf { it > 0 },
                                    durationMs = cursor.getLong(durationCol).takeIf { it > 0 },
                                    volumeLabel = label
                                ),
                                rootPath = rootPath
                            )
                        }
                    }
                }.onFailure { Log.w(TAG, "MediaStore query failed for volume $volume", it) }
            }
        } else {
            @Suppress("DEPRECATION")
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION
            )
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val roots = storageRoots()
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    val root = roots.firstOrNull { path.startsWith(it.absolutePath + "/") }
                    results += IndexedFile(
                        file = LocalVideoFile(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString(),
                            relativePath = root?.let { file.parent?.removePrefix(it.absolutePath) } ?: file.parent.orEmpty(),
                            fileName = file.name,
                            sizeBytes = cursor.getLong(sizeCol).takeIf { it > 0 },
                            durationMs = cursor.getLong(durationCol).takeIf { it > 0 }
                        ),
                        rootPath = root?.absolutePath
                    )
                }
            }
        }
        return results
    }

    /** Root directories of internal storage and every mounted removable volume. */
    private fun storageRoots(): List<File> {
        val roots = linkedSetOf<File>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes?.forEach { volume ->
                if (volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                    volume.directory?.let(roots::add)
                }
            }
        } else {
            // App-specific dirs exist on every mounted volume: /storage/<id>/Android/data/<pkg>/files
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                dir.absolutePath.substringBefore("/Android/data/", "").takeIf { it.isNotEmpty() }
                    ?.let { roots += File(it) }
            }
        }
        return roots.filter { it.canRead() }
    }

    private fun walk(root: File): List<LocalVideoFile> {
        val label = root.name
        val results = mutableListOf<LocalVideoFile>()
        fun visit(dir: File, depth: Int) {
            if (depth > MAX_WALK_DEPTH || results.size >= MAX_WALKED_FILES) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (depth == 0 && child.name == "Android") continue
                    visit(child, depth + 1)
                } else if (child.extension.lowercase() in VIDEO_EXTENSIONS) {
                    results += LocalVideoFile(
                        uri = Uri.fromFile(child).toString(),
                        relativePath = child.parentFile?.absolutePath?.removePrefix(root.absolutePath).orEmpty(),
                        fileName = child.name,
                        sizeBytes = child.length().takeIf { it > 0 },
                        volumeLabel = label
                    )
                }
            }
        }
        runCatching { visit(root, 0) }.onFailure { Log.w(TAG, "Walking ${root.absolutePath} failed", it) }
        return results
    }

    companion object {
        private const val TAG = "LocalMediaLibrary"
        const val MAX_MATCHES = 6
        private const val PREFS_NAME = "local_media"
        private const val KEY_PERMISSION_ASKED = "permission_asked"
        private const val CACHE_TTL_MS = 60_000L
        private const val MAX_WALK_DEPTH = 6
        private const val MAX_WALKED_FILES = 20_000
        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm", "flv", "mpg", "mpeg", "vob", "3gp"
        )
    }
}
