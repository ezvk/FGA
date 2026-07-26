package io.github.fate_grand_automata.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.IStorageProvider
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.lib_automata.Pattern
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Waydroid's ExternalStorageProvider proxies reads through MediaProvider, which throws a
 * NullPointerException resolving the calling package identity in this environment - a platform
 * bug, not something fixable from the SAF/ContentResolver side. When we have full storage access
 * (MANAGE_EXTERNAL_STORAGE), resolve "primary" volume tree/document URIs to a real file path and
 * read directly, skipping SAF/MediaProvider entirely.
 */
private fun Uri.toRealFileOrNull(): File? {
    if (!Environment.isExternalStorageManager()) return null

    val docId = try {
        if (DocumentsContract.isTreeUri(this))
            DocumentsContract.getTreeDocumentId(this)
        else
            DocumentsContract.getDocumentId(this)
    } catch (e: Exception) {
        return null
    }

    val split = docId.split(":", limit = 2)
    if (split.size != 2 || !split[0].equals("primary", ignoreCase = true)) return null

    val file = File(Environment.getExternalStorageDirectory(), split[1])
    return file.takeIf { it.exists() }
}

@Singleton
class StorageProvider @Inject constructor(
    val prefsCore: PrefsCore,
    @param:ApplicationContext val context: Context
) : IStorageProvider {
    fun DocumentFile?.getOrCreateDir(name: String) =
        this?.findFile(name)?.takeIf { it.isDirectory }
            ?: this?.createDirectory(name)
            ?: throw KnownException(KnownException.Reason.CouldNotCreateDirectory(name))

    fun DocumentFile?.getOrCreateFile(name: String, mime: String = mimeAny) =
        this?.findFile(name)?.takeIf { !it.isDirectory }
            ?: this?.createFile(mime, name)
            ?: throw KnownException(KnownException.Reason.CouldNotCreateFile(name))

    val resolver: ContentResolver = context.contentResolver
    private var dirRoot: DocumentFile? = null

    // Direct filesystem path for dirRoot, when resolvable (see toRealFileOrNull). Used to bypass
    // SAF/DocumentFile entirely for support image lookup, since Waydroid's URI-grant persistence
    // is broken system-wide (dumpsys uri_grants comes back empty for every app), which makes every
    // DocumentFile query/listFiles call throw a SecurityException.
    private var dirRootFile: File? = null

    val mimeAny = "*/*"
    val mimePng = "image/png"

    val rootDirName
        get() = dirRoot?.name

    private val recordingFile: DocumentFile
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd-hh-mm-ss", Locale.getDefault())
            val timeString = sdf.format(Date())
            return dirRoot.getOrCreateFile("record-$timeString.mp4")
        }

    val recordingFileDescriptor
        get() = resolver.openFileDescriptor(recordingFile.uri, "rw")
            ?: throw KnownException(KnownException.Reason.CouldNotOpenFileForRecording)

    private val persistablePermission =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun setRoot(rootUri: Uri) {
        prefsCore.dirRoot.get().let { prevDir ->
            // Don't release permission if user picked the same directory again
            if (prevDir.isNotBlank() && prevDir != rootUri.toString()) {
                try {
                    resolver.releasePersistableUriPermission(Uri.parse(prevDir), persistablePermission)
                } catch (e: Exception) {
                    Timber.e(e, "Error on releasing persistable URI")
                }
            }
        }

        prefsCore.dirRoot.set(rootUri.toString())

        try {
            resolver.takePersistableUriPermission(rootUri, persistablePermission)
        } catch (e: Exception) {
            Timber.e(e, "Error on taking persistable URI, will rely on direct file access instead")
        }

        dirRoot = DocumentFile.fromTreeUri(context, rootUri)
        dirRootFile = rootUri.toRealFileOrNull()
    }

    init {
        prefsCore.dirRoot.get().let { dir ->
            if (dir.isNotBlank()) {
                val uri = Uri.parse(dir)
                dirRoot = DocumentFile.fromTreeUri(context, uri)
                dirRootFile = uri.toRealFileOrNull()
            }
        }
    }

    override val supportImageTempDir: File by lazy {
        File(context.cacheDir, "support").apply {
            mkdirs()
        }
    }

    private val supportFolderName = "support"

    val shouldExtractSupportImages
        get() = dirRootFile?.let { !File(it, supportFolderName).isDirectory }
            ?: (dirRoot?.findFile(supportFolderName) == null)

    private val supportImageFolder
        get() = dirRoot.getOrCreateDir(supportFolderName)

    private val supportServantFolder
        get() = supportImageFolder.getOrCreateDir("servant")

    private val supportCEFolder
        get() = supportImageFolder.getOrCreateDir("ce")

    private val supportFriendFolder
        get() = supportImageFolder.getOrCreateDir("friend")

    private val SupportImageKind.imageFolder
        get() = when (this) {
            SupportImageKind.Servant -> supportServantFolder
            SupportImageKind.CE -> supportCEFolder
            SupportImageKind.Friend -> supportFriendFolder
        }

    private val SupportImageKind.folderName
        get() = when (this) {
            SupportImageKind.Servant -> "servant"
            SupportImageKind.CE -> "ce"
            SupportImageKind.Friend -> "friend"
        }

    private fun SupportImageKind.realImageFolder(): File? =
        dirRootFile?.let { File(File(it, supportFolderName), folderName) }

    override fun writeSupportImage(kind: SupportImageKind, name: String): OutputStream {
        val folder = kind.imageFolder

        val indexOfSlash = name.indexOf('/')
        val resolved = if (indexOfSlash == -1) {
            folder.getOrCreateFile(name, mimePng)
        } else {
            val subFolder = name.substring(0 until indexOfSlash)
            val fileName = name.substring(indexOfSlash + 1)

            folder
                .getOrCreateDir(subFolder)
                .getOrCreateFile(fileName, mimePng)
        }

        return resolver.openOutputStream(resolved.uri)
            ?: throw KnownException(
                KnownException.Reason.CouldNotOpenSupportFileForWriting(kind, name)
            )
    }

    override fun readSupportImage(kind: SupportImageKind, name: String): List<InputStream> {
        kind.realImageFolder()?.let { folder ->
            val target = File(folder, name)

            val files: List<File> = when {
                target.isDirectory -> target.listFiles()
                    ?.filter { !it.name.lowercase().contains("nomedia") }
                    ?.takeUnless { it.isEmpty() }
                    ?: throw KnownException(KnownException.Reason.SupportFolderIsEmpty(kind, name))

                target.isFile -> listOf(target)

                else -> throw KnownException(
                    KnownException.Reason.CouldNotOpenSupportFileForReading(kind, name)
                )
            }

            return files.map { FileInputStream(it) }
        }

        val file = kind.imageFolder.findFile(name)
            ?: throw KnownException(
                KnownException.Reason.CouldNotOpenSupportFileForReading(kind, name)
            )

        val files: List<DocumentFile> = if (file.isDirectory) {
            file.listFiles()
                .takeUnless { it.isEmpty() }
                ?.filter { it.name != null && !it.name!!.lowercase().contains("nomedia") }
                ?: throw KnownException(KnownException.Reason.SupportFolderIsEmpty(kind, name))
        } else listOf(file)

        return files.map {
            resolver.openInputStream(it.uri)
                ?: throw KnownException(
                    KnownException.Reason.CouldNotOpenSupportFileForReading(kind, name)
                )
        }
    }

    override fun list(kind: SupportImageKind): List<String> {
        kind.realImageFolder()?.let { folder ->
            return folder.listFiles()
                ?.mapNotNull { it.name }
                ?.filterNot { it.lowercase().contains("nomedia") }
                ?: emptyList()
        }

        return kind.imageFolder
            .listFiles()
            .mapNotNull { it.name }
            .filterNot { it.lowercase().contains("nomedia") }
    }

    private val dropsFolder
        get() = dirRoot.getOrCreateDir("drops")

    override fun dropScreenshot(patterns: List<Pattern>) {
        val sdf = SimpleDateFormat("yyyy-MM-dd-hh-mm-ss", Locale.getDefault())
        val timeString = sdf.format(Date())

        for ((i, pattern) in patterns.withIndex()) {
            val dropFileName = "${timeString}.${i}.png"

            pattern.use {
                val file = dropsFolder.createFile(mimePng, dropFileName)
                    ?: throw KnownException(KnownException.Reason.CouldNotCreateDropScreenshotFile)

                resolver.openOutputStream(file.uri)?.use { stream ->
                    pattern.save(stream)
                }
            }
        }
    }

    private val bondFolder
        get() = dirRoot.getOrCreateDir("bond")

    override fun dropBondScreenShot(pattern: Pattern, server: GameServer) {
        val sdf = SimpleDateFormat("yyyy-MM-dd-hh-mm-ss", Locale.getDefault())
        val timeString = sdf.format(Date())

        val dropFileName = "bond-${server.simple.uppercase()}-${timeString}.png"

        pattern.use {
            val file = bondFolder.createFile(mimePng, dropFileName)
                ?: throw KnownException(KnownException.Reason.CouldNotCreateDropScreenshotFile)

            resolver.openOutputStream(file.uri)?.use { stream ->
                pattern.save(stream)
            }
        }
    }

    override fun dump(name: String, image: Pattern) {
        image.use {
            if (BuildConfig.DEBUG) {
                val file = dirRoot
                    .getOrCreateDir("dump")
                    .getOrCreateFile(name)

                resolver.openOutputStream(file.uri)?.use { stream ->
                    image.save(stream)
                }
            }
        }
    }

    override fun createNoMediaFile() {
        supportImageFolder.getOrCreateFile(".nomedia")
    }
}