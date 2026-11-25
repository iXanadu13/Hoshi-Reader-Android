package moe.antimony.hoshi.features.audio

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordAudioCacheTest {
    @Test
    fun keepsAllSourcesForOneWordAndClearsSourcesIndependently() = runBlocking {
        val root = Files.createTempDirectory("hoshi-word-audio-cache").toFile()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val cache = WordAudioCache(root, Dispatchers.Unconfined, scope)
            val jpodUrl = "https://audio.example/jpod.mp3"
            val jishoUrl = "https://audio.example/jisho.mp3"
            cache.store("猫", "ねこ", "jpod101", jpodUrl, byteArrayOf(1))
            cache.store("猫", "ねこ", "jisho", jishoUrl, byteArrayOf(2))

            assertEquals(listOf(jpodUrl), cache.cachedCandidates("猫", "ねこ", "jpod101").map { it.url })
            assertEquals(listOf(jishoUrl), cache.cachedCandidates("猫", "ねこ", "jisho").map { it.url })
            assertEquals(1, cache.state.value.cachedWordCount)

            cache.clearSource("猫", "ねこ", "jpod101")
            assertTrue(cache.cachedCandidates("猫", "ねこ", "jpod101").isEmpty())
            assertEquals(listOf(jishoUrl), cache.cachedCandidates("猫", "ねこ", "jisho").map { it.url })

            cache.clearAll()
            assertEquals(0, cache.state.value.cachedWordCount)
            assertNull(cache.cachedFile("猫", "ねこ", jishoUrl))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun persistsAcrossInstancesAndEvictsByWordCount() = runBlocking {
        val root = Files.createTempDirectory("hoshi-word-audio-cache").toFile()
        val firstScope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val first = WordAudioCache(root, Dispatchers.Unconfined, firstScope)
            val catUrl = "https://audio.example/cat.mp3"
            first.store("猫", "ねこ", "jpod101", catUrl, byteArrayOf(1))
            assertNotNull(first.cachedFile("猫", "ねこ", catUrl))
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val second = WordAudioCache(root, Dispatchers.Unconfined, secondScope)
            val dogUrl = "https://audio.example/dog.mp3"
            second.setMaxWordCount(1)
            second.store("犬", "いぬ", "jpod101", dogUrl, byteArrayOf(2))

            assertEquals(1, second.state.value.cachedWordCount)
            assertNotNull(second.cachedFile("犬", "いぬ", dogUrl))
            assertNull(second.cachedFile("猫", "ねこ", "https://audio.example/cat.mp3"))
        } finally {
            secondScope.cancel()
            root.deleteRecursively()
        }
    }
}
