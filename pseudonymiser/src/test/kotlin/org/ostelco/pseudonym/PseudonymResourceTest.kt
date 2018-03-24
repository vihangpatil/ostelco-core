package org.ostelco.pseudonym

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.cloud.datastore.Datastore
import com.google.cloud.datastore.testing.LocalDatastoreHelper
import io.dropwizard.testing.junit.ResourceTestRule
import org.junit.ClassRule
import org.junit.Test
import org.ostelco.pseudonym.resources.PseudonymEntity
import org.ostelco.pseudonym.resources.PseudonymResource
import org.ostelco.pseudonym.utils.WeeklyBounds
import javax.ws.rs.core.Response.Status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Class for unit testing PseudonymResource.
 */
class PseudonymResourceTest {

    companion object {

        private var datastore: Datastore

        init {
            val helper: LocalDatastoreHelper = LocalDatastoreHelper.create(1.0)
            helper.start()
            datastore = helper.options.service
        }

        @ClassRule
        @JvmField
        val resources = ResourceTestRule.builder()
                .addResource(PseudonymResource(datastore, WeeklyBounds()))
                .build()
    }
    val mapper = jacksonObjectMapper()
    /**
     * Test what happens when parameter is not given
     */
    @Test
    fun testPseudonymResourceForMissingParameter() {
        System.out.println("testPseudonymResourceForMissingHeader")

        val statusCode = resources
                ?.target("/pseudonym/current/")
                ?.request()
                ?.get()
                ?.status ?: -1

        assertEquals(Status.NOT_FOUND.statusCode, statusCode)
    }
    /**
     * Test a normal request will all parameters
     */
    @Test
    fun testCurrentPseudonym() {
        val statusCode = resources
                ?.target("/pseudonym/current/4790303333")
                ?.request()
                ?.get()
                ?.status ?: -1

        assertEquals(Status.OK.statusCode, statusCode)
    }

    /**
     * Test get pseudonym for a timestamp
     */
    @Test
    fun testGetPseudonym() {

        var result = resources
                ?.target("/pseudonym/current/4790303333")
                ?.request()
                ?.get()
        assertNotNull(result)
        if (result == null) return
        assertEquals(Status.OK.statusCode, result.status)
        var json = result.readEntity(String::class.java)
        var pseudonymEntity = mapper.readValue<PseudonymEntity>(json)
        assertEquals(pseudonymEntity.msisdn, "4790303333")

        result = resources
                ?.target("/pseudonym/get/4790303333/${pseudonymEntity.start}")
                ?.request()
                ?.get()
        assertNotNull(result)
        if (result == null) return
        assertEquals(Status.OK.statusCode, result.status)
        json = result.readEntity(String::class.java)
        val pseudonymEntity2 = mapper.readValue<PseudonymEntity>(json)
        assertEquals(pseudonymEntity2.pseudonym, pseudonymEntity.pseudonym)
    }

    /**
     * Test a finding a pseudonym
     */
    @Test
    fun testFindPseudonym() {

        var result = resources
                ?.target("/pseudonym/current/4790303333")
                ?.request()
                ?.get()
        assertNotNull(result)
        if (result == null) return
        assertEquals(Status.OK.statusCode, result.status)
        var json = result.readEntity(String::class.java)
        var pseudonymEntity = mapper.readValue<PseudonymEntity>(json)
        assertEquals(pseudonymEntity.msisdn, "4790303333")

        result = resources
                ?.target("/pseudonym/find/${pseudonymEntity.pseudonym}")
                ?.request()
                ?.get()
        assertNotNull(result)
        if (result == null) return
        assertEquals(Status.OK.statusCode, result.status)
        json = result.readEntity(String::class.java)
        pseudonymEntity = mapper.readValue<PseudonymEntity>(json)
        assertEquals(pseudonymEntity.msisdn, "4790303333")
    }

    /**
     * Test deleting all pseudonyms for a msisdn
     */
    @Test
    fun testDeletePseudonym() {
        val testMsisdn = "4790309999"
        var result = resources
                ?.target("/pseudonym/current/$testMsisdn")
                ?.request()
                ?.get()
        assertNotNull(result)
        if (result == null) return
        assertEquals(Status.OK.statusCode, result.status)
        var json = result.readEntity(String::class.java)
        var pseudonymEntity = mapper.readValue<PseudonymEntity>(json)
        assertEquals(pseudonymEntity.msisdn, testMsisdn)

        result = resources
                ?.target("/pseudonym/delete/$testMsisdn")
                ?.request()
                ?.delete()
        assertNotNull(result)
        if (result == null) return
        assertEquals(Status.OK.statusCode, result.status)
        json = result.readEntity(String::class.java)
        val countMap = mapper.readValue<Map<String, Int>>(json)
        val count = countMap["count"] ?: -1
        assertTrue(count >= 1)

        result = resources
                ?.target("/pseudonym/find/${pseudonymEntity.pseudonym}")
                ?.request()
                ?.get()
        assertNotNull(result)
        if (result == null) return
        assertEquals(Status.NOT_FOUND.statusCode, result.status)
    }
}