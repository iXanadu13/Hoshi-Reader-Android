package moe.antimony.hoshi.features.statistics

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupStatisticsStoreTest {
    @Test
    fun storesGlobalReadingStyleJsonSessions() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-lookup-statistics").toFile()
        try {
            LookupStatisticsStore(filesDir, Dispatchers.IO).append(
                LookupStatisticsSession(startedAt = 1_000, endedAt = 4_000, lookupCount = 2),
            )

            val file = filesDir.resolve("Dictionaries/statistics.json")
            assertTrue(file.isFile)
            assertTrue(file.readText().contains("\"startedAt\""))
            assertEquals(
                listOf(2),
                LookupStatisticsStore(filesDir, Dispatchers.IO).loadSessions().values.mapNotNull { it.value }.map { it.lookupCount },
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
