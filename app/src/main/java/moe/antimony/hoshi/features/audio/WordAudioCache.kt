package moe.antimony.hoshi.features.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.IoDispatcher

internal const val DefaultWordAudioCacheLimit = 100

data class WordAudioCacheState(
    val cachedWordCount: Int = 0,
    val cachedAudioSizeBytes: Long = 0L,
    val maxWordCount: Int = DefaultWordAudioCacheLimit,
)

data class CachedWordAudioCandidate(
    val url: String,
    val name: String = "",
)

@Serializable
private data class WordAudioCacheEntry(
    val wordKey: String,
    val sourceKey: String,
    val url: String,
    val fileName: String,
    val name: String = "",
    val lastAccess: Long,
)

@Serializable
private data class WordAudioCacheIndex(
    val maxWordCount: Int = DefaultWordAudioCacheLimit,
    val entries: List<WordAudioCacheEntry> = emptyList(),
)

@Singleton
class WordAudioCache internal constructor(
    private val root: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope applicationScope: CoroutineScope,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(File(context.noBackupFilesDir, "word-audio"), ioDispatcher, applicationScope)

    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val indexLock = Mutex()
    private val urlLocks = ConcurrentHashMap<String, Mutex>()
    private val mutableState = MutableStateFlow(WordAudioCacheState())
    private var loaded = false
    private var index = WordAudioCacheIndex()
    private var epoch = 0L

    val state = mutableState.asStateFlow()

    init {
        applicationScope.launch(ioDispatcher) {
            indexLock.withLock { ensureLoadedLocked() }
        }
    }

    suspend fun cachedCandidates(term: String, reading: String, sourceKey: String): List<CachedWordAudioCandidate> =
        withContext(ioDispatcher) {
            indexLock.withLock {
                ensureLoadedLocked()
                val wordKey = wordKey(term, reading)
                val matching = index.entries.filter { entry ->
                    entry.wordKey == wordKey && entry.sourceKey == sourceKey && fileFor(entry).isFile
                }
                val stale = index.entries.filter { it.wordKey == wordKey && !fileFor(it).isFile }
                if (stale.isNotEmpty()) {
                    index = index.copy(entries = index.entries - stale.toSet())
                    publishStateLocked()
                }
                if (matching.isNotEmpty()) {
                    val now = nextAccessTimeLocked()
                    val urls = matching.mapTo(mutableSetOf()) { it.url }
                    index = index.copy(entries = index.entries.map { entry ->
                        if (entry.wordKey == wordKey && entry.url in urls) entry.copy(lastAccess = now) else entry
                    })
                }
                persistLocked()
                matching.map { CachedWordAudioCandidate(it.url, it.name) }.distinctBy { it.url }
            }
        }

    suspend fun cachedFile(term: String, reading: String, url: String): File? =
        withContext(ioDispatcher) {
            indexLock.withLock {
                ensureLoadedLocked()
                val wordKey = wordKey(term, reading)
                val entry = index.entries.firstOrNull { it.wordKey == wordKey && it.url == url }
                val file = entry?.let(::fileFor)
                if (file?.isFile != true) {
                    if (entry != null) {
                        index = index.copy(entries = index.entries - entry)
                        persistLocked()
                        publishStateLocked()
                    }
                    null
                } else {
                    index = index.copy(entries = index.entries.map {
                        if (it.wordKey == wordKey && it.url == url) it.copy(lastAccess = nextAccessTimeLocked()) else it
                    })
                    persistLocked()
                    file
                }
            }
        }

    suspend fun getOrDownload(
        term: String,
        reading: String,
        sourceKey: String,
        url: String,
        displayName: String = "",
        forceRefresh: Boolean = false,
        download: suspend () -> ByteArray?,
    ): File? {
        if (!url.isRemoteAudioUrl()) return null
        val lockKey = "${wordKey(term, reading)}\u0000$url"
        val lock = urlLocks.computeIfAbsent(lockKey) { Mutex() }
        return withContext(ioDispatcher) {
            lock.withLock {
                if (!forceRefresh) cachedFile(term, reading, url)?.also {
                    if (displayName.isNotBlank()) associate(term, reading, sourceKey, url, it, displayName)
                }?.let { return@withLock it }
                val requestEpoch = currentEpoch()
                val body = download()?.takeIf { it.isNotEmpty() } ?: return@withLock null
                store(term, reading, sourceKey, url, body, displayName, requestEpoch)
            }
        }
    }

    suspend fun store(
        term: String,
        reading: String,
        sourceKey: String,
        url: String,
        bytes: ByteArray,
        displayName: String = "",
    ): File? = store(term, reading, sourceKey, url, bytes, displayName, currentEpoch())

    suspend fun clearSource(term: String, reading: String, sourceKey: String) {
        withContext(ioDispatcher) {
            indexLock.withLock {
                ensureLoadedLocked()
                removeEntriesLocked(wordKey(term, reading)) { it.sourceKey == sourceKey }
            }
        }
    }

    suspend fun clearWord(term: String, reading: String) {
        withContext(ioDispatcher) {
            indexLock.withLock {
                ensureLoadedLocked()
                removeEntriesLocked(wordKey(term, reading)) { true }
            }
        }
    }

    suspend fun clearAll() {
        withContext(ioDispatcher) {
            indexLock.withLock {
                ensureLoadedLocked()
                epoch++
                root.deleteRecursively()
                root.mkdirs()
                index = WordAudioCacheIndex(maxWordCount = index.maxWordCount)
                persistLocked()
                publishStateLocked()
            }
        }
    }

    suspend fun setMaxWordCount(value: Int) {
        withContext(ioDispatcher) {
            indexLock.withLock {
                ensureLoadedLocked()
                index = index.copy(maxWordCount = value.coerceIn(MinWordAudioCacheLimit, MaxWordAudioCacheLimit))
                trimLocked()
                persistLocked()
                publishStateLocked()
            }
        }
    }

    private suspend fun store(
        term: String,
        reading: String,
        sourceKey: String,
        url: String,
        bytes: ByteArray,
        displayName: String,
        expectedEpoch: Long?,
    ): File? = withContext(ioDispatcher) {
        indexLock.withLock {
            ensureLoadedLocked()
            if (expectedEpoch != null && expectedEpoch != epoch) return@withLock null
            val wordKey = wordKey(term, reading)
            val wordDirectory = File(root, wordKey)
            if (!wordDirectory.exists() && !wordDirectory.mkdirs()) return@withLock null
            val target = File(wordDirectory, audioFileName(url))
            val temporary = File.createTempFile(".audio-", ".tmp", wordDirectory)
            try {
                temporary.writeBytes(bytes)
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                }
                associateLocked(term, reading, sourceKey, url, target, displayName)
                trimLocked()
                persistLocked()
                publishStateLocked()
                target.takeIf { it.isFile }
            } finally {
                temporary.delete()
            }
        }
    }

    private suspend fun associate(
        term: String,
        reading: String,
        sourceKey: String,
        url: String,
        file: File,
        displayName: String,
    ) {
        withContext(ioDispatcher) {
            indexLock.withLock {
                ensureLoadedLocked()
                associateLocked(term, reading, sourceKey, url, file, displayName)
                persistLocked()
                publishStateLocked()
            }
        }
    }

    private fun associateLocked(
        term: String,
        reading: String,
        sourceKey: String,
        url: String,
        file: File,
        displayName: String,
    ) {
        val wordKey = wordKey(term, reading)
        val now = nextAccessTimeLocked()
        val existing = index.entries.firstOrNull { it.wordKey == wordKey && it.sourceKey == sourceKey && it.url == url }
        val next = WordAudioCacheEntry(
            wordKey = wordKey,
            sourceKey = sourceKey,
            url = url,
            fileName = file.name,
            name = displayName.ifBlank { existing?.name.orEmpty() },
            lastAccess = now,
        )
        index = index.copy(entries = index.entries.filterNot {
            it.wordKey == wordKey && it.sourceKey == sourceKey && it.url == url
        } + next)
    }

    private fun removeEntriesLocked(wordKey: String, matches: (WordAudioCacheEntry) -> Boolean) {
        val removed = index.entries.filter { it.wordKey == wordKey && matches(it) }
        if (removed.isEmpty()) return
        val remaining = index.entries - removed.toSet()
        removed.map { File(File(root, it.wordKey), it.fileName) }
            .filter { file -> remaining.none { it.wordKey == wordKey && File(File(root, it.wordKey), it.fileName) == file } }
            .forEach(File::delete)
        index = index.copy(entries = remaining)
        val wordDirectory = File(root, wordKey)
        if (wordDirectory.listFiles()?.isEmpty() == true) wordDirectory.delete()
        persistLocked()
        publishStateLocked()
    }

    private fun trimLocked() {
        val words = index.entries.groupBy { it.wordKey }
        if (words.size <= index.maxWordCount) return
        val toRemove = words.entries
            .sortedBy { (_, entries) -> entries.maxOfOrNull { it.lastAccess } ?: 0L }
            .take(words.size - index.maxWordCount)
            .map { it.key }
            .toSet()
        toRemove.forEach { wordKey ->
            File(root, wordKey).deleteRecursively()
        }
        index = index.copy(entries = index.entries.filterNot { it.wordKey in toRemove })
    }

    private fun fileFor(entry: WordAudioCacheEntry): File = File(File(root, entry.wordKey), entry.fileName)

    private fun nextAccessTimeLocked(): Long = maxOf(
        System.currentTimeMillis(),
        (index.entries.maxOfOrNull { it.lastAccess } ?: 0L) + 1,
    )

    private fun ensureLoadedLocked() {
        if (loaded) return
        root.mkdirs()
        index = runCatching {
            if (indexFile.isFile) json.decodeFromString<WordAudioCacheIndex>(indexFile.readText()) else WordAudioCacheIndex()
        }.getOrDefault(WordAudioCacheIndex())
            .copy(maxWordCount = index.maxWordCount.coerceIn(MinWordAudioCacheLimit, MaxWordAudioCacheLimit))
        val existing = index.entries.filter { fileFor(it).isFile }
        index = index.copy(entries = existing)
        loaded = true
        publishStateLocked()
    }

    private fun persistLocked() {
        root.mkdirs()
        val temporary = File(root, "index.json.tmp")
        temporary.writeText(json.encodeToString(WordAudioCacheIndex.serializer(), index))
        try {
            Files.move(
                temporary.toPath(),
                indexFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            if (!temporary.renameTo(indexFile)) {
                temporary.copyTo(indexFile, overwrite = true)
                temporary.delete()
            }
        }
    }

    private suspend fun currentEpoch(): Long = indexLock.withLock {
        ensureLoadedLocked()
        epoch
    }

    private fun publishStateLocked() {
        val existingEntries = index.entries.filter { fileFor(it).isFile }
        mutableState.value = WordAudioCacheState(
            cachedWordCount = existingEntries.mapTo(mutableSetOf()) { it.wordKey }.size,
            cachedAudioSizeBytes = existingEntries
                .map(::fileFor)
                .distinctBy(File::getAbsolutePath)
                .sumOf(File::length),
            maxWordCount = index.maxWordCount,
        )
    }

    companion object {
        const val MinWordAudioCacheLimit = 1
        const val MaxWordAudioCacheLimit = 10_000

        internal fun wordKey(term: String, reading: String): String =
            sha256("$term\u0000$reading")

        private fun audioFileName(url: String): String {
            val extension = runCatching { URI(url).path.orEmpty().substringAfterLast('.', "") }
                .getOrNull()
                ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
                ?.lowercase()
                ?: "audio"
            return "audio-${sha256(url).take(32)}.$extension"
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

internal fun String.isRemoteAudioUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
