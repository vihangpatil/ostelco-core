package org.ostelco.storage.scaninfo.shredder

import com.google.cloud.datastore.DatastoreException
import com.google.cloud.datastore.Entity
import com.google.cloud.datastore.Query
import com.google.cloud.datastore.StructuredQuery
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.mockito.Mockito
import org.ostelco.prime.model.ScanMetadata
import org.ostelco.prime.model.ScanMetadataEnum
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Class for testing the Datastore queries.
 */
class MetadataQueryTest {

    private fun saveScanMetaData(customerId: String, countryCode: String, id: String, scanReference: String, time: Long): Boolean {
        val keyString = "$customerId-${id}"
        try {
            val key = scanInfoShredder.keyFactory.newKey(keyString)
            val entity = Entity.newBuilder(key)
                    .set(ScanMetadataEnum.ID.s, id)
                    .set(ScanMetadataEnum.SCAN_REFERENCE.s, scanReference)
                    .set(ScanMetadataEnum.COUNTRY_CODE.s, countryCode)
                    .set(ScanMetadataEnum.CUSTOMER_ID.s, customerId)
                    .set(ScanMetadataEnum.PROCESSED_TIME.s, time)
                    .build()
            scanInfoShredder.datastore.add(entity)
        } catch (e: DatastoreException) {
            return false
        }
        return true
    }

    @Test
    fun testShred() {
        var testTime = Instant.now().toEpochMilli() - (scanInfoShredder.expiryDuration) - 10000
        // Add 200 records
        for (i in 1..200) {
            saveScanMetaData("cid1", "sgp", "id{$i}", "ref${i}", testTime)
            if (i == 100) {
                testTime = Instant.now().toEpochMilli()
            }
        }
        runBlocking {
            val totalItems = scanInfoShredder.shred()
            assertEquals(100, totalItems, "Missing some items while scanning for items")
            val query = Query.newEntityQueryBuilder()
                    .setKind(ScanMetadataEnum.KIND.s)
                    .setLimit(1000)
                    .build()
            val resultList = scanInfoShredder.datastore.run(query)
            var count  = 0
            while (resultList.hasNext()) {
                resultList.next()
                count++
            }
            assertEquals(100, totalItems, "Non expected count")
        }
    }

    companion object {
        private lateinit var scanInfoShredder:ScanInfoShredder

        @JvmStatic
        @BeforeClass
        fun init() {
            File("encrypt_key_global").delete()
            val testEnvVars = Mockito.mock(EnvironmentVars::class.java)
            Mockito.`when`(testEnvVars.getVar("JUMIO_API_TOKEN")).thenReturn("")
            Mockito.`when`(testEnvVars.getVar("JUMIO_API_SECRET")).thenReturn("")
            Mockito.`when`(testEnvVars.getVar("SCANINFO_STORAGE_BUCKET")).thenReturn("")
             val config = ScanInfoShredderConfig()
                    .apply { this.storeType = "inmemory-emulator" }
            scanInfoShredder = ScanInfoShredder(config)
            scanInfoShredder.init(testEnvVars)
        }

        @JvmStatic
        @AfterClass
        fun cleanup() {
            scanInfoShredder.cleanup()
        }
    }
}