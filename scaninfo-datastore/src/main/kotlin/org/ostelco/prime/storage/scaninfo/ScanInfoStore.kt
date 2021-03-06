package org.ostelco.prime.storage.scaninfo

import arrow.core.Either
import arrow.core.extensions.fx
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.cloud.datastore.Blob
import com.google.cloud.datastore.DatastoreException
import com.google.crypto.tink.config.TinkConfig
import org.ostelco.prime.getLogger
import org.ostelco.prime.jsonmapper.objectMapper
import org.ostelco.prime.model.*
import org.ostelco.prime.module.getResource
import org.ostelco.prime.securearchive.SecureArchiveService
import org.ostelco.prime.storage.FileDownloadError
import org.ostelco.prime.storage.NotCreatedError
import org.ostelco.prime.storage.ScanInformationStore
import org.ostelco.prime.storage.StoreError
import org.ostelco.prime.store.datastore.EntityStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.*
import javax.ws.rs.core.MultivaluedMap


class ScanInfoStore : ScanInformationStore by ScanInformationStoreSingleton

/**
 * Helper class for getting environment variables.
 * Introduced to help testing.
 */
open class EnvironmentVars {
    /**
     * Retrieve the value of the environment variable.
     */
    open fun getVar(name: String): String? = System.getenv(name)
}

/**
 * Object implementing the Cloud-Storage, Jumio based Scan Store
 */
object ScanInformationStoreSingleton : ScanInformationStore {

    private val logger by getLogger()

    internal val scanMetadataStore = EntityStore(
            entityClass = ScanMetadata::class,
            type = ConfigRegistry.config.storeType,
            namespace = ConfigRegistry.config.namespace)

    /* Generated by Jumio and can be obtained from the console. */
    private lateinit var apiToken: String
    private lateinit var apiSecret: String

    private val secureArchiveService by lazy { getResource<SecureArchiveService>() }

    /**
     * Save the scan information in cloud storage.
     * Downloads images and create the zip file. The zip file is then
     * encrypted with the keys for right buckets.
     *  - Saves the zip files in two locations,
     *  1) <bucket>-global/<customer-id>/<scan-id>.zip.tk
     *  2) <bucket>-<country-code>/<customer-id>/<scan-id>.zip.tk
     */
    override fun upsertVendorScanInformation(customerId: String, countryCode: String, vendorData: MultivaluedMap<String, String>): Either<StoreError, Unit> {

        return Either.fx {
            logger.info("Creating createVendorScanInformation for customerId = $customerId")
            val vendorScanInformation = createVendorScanInformation(vendorData).bind()
            logger.info("Generating data map for customerId = $customerId")
            val dataMap = JumioHelper.toDataMap(vendorScanInformation)
            secureArchiveService.archiveEncrypted(
                    customerId = customerId,
                    regionCodes = listOf(countryCode),
                    fileName = vendorScanInformation.id,
                    dataMap = dataMap).bind()
            saveScanMetaData(customerId, countryCode, vendorScanInformation).bind()
            Unit
        }
    }

    override fun getExtendedStatusInformation(scanInformation: ScanInformation): Map<String, String> {
        return JumioHelper.getExtendedStatusInformation(scanInformation)
    }

    private fun createVendorScanInformation(vendorData: MultivaluedMap<String, String>): Either<StoreError, VendorScanInformation> {
        return JumioHelper.generateVendorScanInformation(vendorData, apiToken, apiSecret)
    }

    private fun saveScanMetaData(customerId: String, countryCode: String, vendorScanInformation: VendorScanInformation): Either<StoreError, Unit> {
        val keyString = "$customerId-${vendorScanInformation.id}"
        try {
            val scanMetadata = ScanMetadata(
                    id = vendorScanInformation.id,
                    scanReference = vendorScanInformation.scanReference,
                    countryCode = countryCode,
                    customerId = customerId,
                    processedTime = Instant.now().toEpochMilli())
            scanMetadataStore.add(scanMetadata, keyString)
            logger.info("Saved ScanMetaData for customerId = $customerId key = $keyString")
        } catch (e: DatastoreException) {
            logger.error("Caught exception while storing the scan meta data", e)
            return Either.left(NotCreatedError("ScanMetaData", keyString))
        }
        return Unit.right()
    }

    // Internal function used by unit test to check the encrypted zip file
    internal fun __getVendorScanInformationFile(countryCode: String, scanId: String): Either<StoreError, String> {
        return Either.right("${countryCode}_$scanId.zip.tk")
    }

    // Internal function used by unit test to check the scan meta data
    internal fun __getScanMetaData(customerId: String, scanId: String): Either<Throwable, ScanMetadata> {
        val keyString = "$customerId-$scanId"
        return try {
            scanMetadataStore.fetch(keyString)
        } catch (e: DatastoreException) {
            logger.error("Caught exception while retreiving scan meta data", e)
            e.left()
        }
    }

    // Initialize the object, get all the environment variables and initialize the encrypter library.
    fun init(environmentVars: EnvironmentVars) {
        TinkConfig.register()
        val storeType = ConfigRegistry.config.storeType
        if (storeType != "emulator" && storeType != "inmemory-emulator") {
            apiToken = environmentVars.getVar("JUMIO_API_TOKEN")
                    ?: throw Error("Missing environment variable JUMIO_API_TOKEN")
            apiSecret = environmentVars.getVar("JUMIO_API_SECRET")
                    ?: throw Error("Missing environment variable JUMIO_API_SECRET")
        } else {
            // Don't throw error during local tests
            apiToken = ""
            apiSecret = ""
        }
    }
}

/**
 * A utility for downloading and creating the scan information for Jumio clients.
 */
object JumioHelper {

    private val logger by getLogger()

    /**
     * Retrieves the contents of a file from a URL
     */
    private fun downloadFileAsBlob(fileURL: String, username: String, password: String): Either<StoreError, Pair<Blob, String>> {
        val url = URL(fileURL)
        val httpConn = url.openConnection() as HttpURLConnection
        val userPassword = "$username:$password"
        val authHeader = "Basic ${Base64.getEncoder().encodeToString(userPassword.toByteArray())}"
        httpConn.setRequestProperty("Authorization", authHeader)

        try {
            val responseCode = httpConn.responseCode
            // always check HTTP response code first
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val statusMessage = "$responseCode: ${httpConn.responseMessage}"
                logger.error("Failed to download $fileURL $statusMessage")
                return Either.left(FileDownloadError(fileURL, statusMessage))
            }
            val contentType = httpConn.contentType
            val inputStream = httpConn.inputStream
            val fileData = Blob.copyFrom(inputStream)
            inputStream.close()
            return Either.right(Pair(fileData, contentType))
        } catch (e: IOException) {
            val statusMessage = "IOException: $e"
            logger.error("Failed to download $fileURL $statusMessage")
            return Either.left(FileDownloadError(fileURL, statusMessage))
        } finally {
            httpConn.disconnect()
        }
    }

    private fun isJSONArray(jsonData: String): Boolean {
        try {
            val mapper = ObjectMapper()
            return mapper.readTree(jsonData).isArray
        } catch (e: IOException) {
            return false
        }
    }

    private fun flattenList(list: List<String>): List<String> {
        try {
            if (list.size > 1) {
                return list //already flattened.
            }
            val jsonData: String = list[0]
            if (isJSONArray(jsonData)) {
                return ObjectMapper().readValue(jsonData)
            }
        } catch (e: IOException) {
            logger.error("Cannot flattenList Json Data $list", e)
        }
        return list
    }

    /**
     * Creates the VendorScanInformation from the input map.
     * - Downloads all the required images
     */
    fun generateVendorScanInformation(vendorData: MultivaluedMap<String, String>, apiToken: String, apiSecret: String): Either<StoreError, VendorScanInformation> {
        val images: MutableMap<String, Blob> = mutableMapOf()

        val scanId: String = vendorData.getFirst(JumioScanData.SCAN_ID.s)
        val scanReference: String = vendorData.getFirst(JumioScanData.JUMIO_SCAN_ID.s)
        val scanDetails: String = ObjectMapper().writeValueAsString(vendorData)
        val scanImageUrl: String? = vendorData.getFirst(JumioScanData.SCAN_IMAGE.s)
        val scanImageBacksideUrl: String? = vendorData.getFirst(JumioScanData.SCAN_IMAGE_BACKSIDE.s)
        val scanImageFaceUrl: String? = vendorData.getFirst(JumioScanData.SCAN_IMAGE_FACE.s)
        val scanLivenessImagesUrl: List<String>? = vendorData[JumioScanData.SCAN_LIVENESS_IMAGES.s]

        return Either.fx {
            var result: Pair<Blob, String>
            if (scanImageUrl != null) {
                logger.info("Downloading scan image: $scanImageUrl")
                result = downloadFileAsBlob(scanImageUrl, apiToken, apiSecret).bind()
                val filename = "id.${getFileExtFromType(result.second)}"
                images.put(filename, result.first)
            }
            if (scanImageBacksideUrl != null) {
                logger.info("Downloading scan image back: $scanImageBacksideUrl")
                result = downloadFileAsBlob(scanImageBacksideUrl, apiToken, apiSecret).bind()
                val filename = "id_backside.${getFileExtFromType(result.second)}"
                images.put(filename, result.first)
            }
            if (scanImageFaceUrl != null) {
                logger.info("Downloading Face Image: $scanImageFaceUrl")
                result = downloadFileAsBlob(scanImageFaceUrl, apiToken, apiSecret).bind()
                val filename = "face.${getFileExtFromType(result.second)}"
                images.put(filename, result.first)
            }
            if (scanLivenessImagesUrl != null) {
                val urls = scanLivenessImagesUrl.toMutableList()
                urls.sort() // The url list is not in sequence
                val flattenedList = flattenList(urls)
                var imageIndex = 0
                for (imageUrl in flattenedList) {
                    logger.info("Downloading Liveness image: $imageUrl")
                    result = downloadFileAsBlob(imageUrl, apiToken, apiSecret).bind()
                    val filename = "liveness-${++imageIndex}.${getFileExtFromType(result.second)}"
                    images.put(filename, result.first)
                }
            }
            VendorScanInformation(scanId, scanReference, scanDetails, images)
        }
    }

    private fun toRegularMap(jsonData: String?): Map<String, String>? {
        try {
            if (jsonData != null) {
                return ObjectMapper().readValue(jsonData)
            }
        } catch (e: IOException) {
            logger.error("Cannot parse Json Data: $jsonData")
        }
        return null
    }
    internal fun toIdentityVerification(jsonData: String?): IdentityVerification? {
        try {
            if (jsonData != null) {
                return ObjectMapper().readValue(jsonData)
            }
        } catch (e: IOException) {
            logger.error("Cannot parse Json Data: $jsonData", e)
        }
        return null
    }
    /**
     * Constructs extended status information from Jumio callback data.
     */
    fun getExtendedStatusInformation(scanInformation: ScanInformation): Map<String, String> {
        val extendedStatus = mutableMapOf<String, String>()
        extendedStatus.putIfAbsent(JumioScanData.SCAN_INFORMATION.s, objectMapper.writeValueAsString(scanInformation))
        return extendedStatus
    }

    /**
     * Creates the map of VendorScanInformation and Images.
     */
    fun toDataMap(vendorData: VendorScanInformation): Map<String, ByteArray> =
            mapOf("postdata.json" to vendorData.details.toByteArray()) +
                (vendorData.images?.mapValues { it.value.toByteArray() } ?: emptyMap())

    /**
     * Creates the file extension from  mime-type.
     */
    private fun getFileExtFromType(mimeType: String): String {
        val idx = mimeType.lastIndexOf("/")
        if (idx == -1) {
            return mimeType
        } else {
            return mimeType.drop(idx + 1)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val fileURL = "https://jdbc.postgresql.org/download/postgresql-9.2-1002.jdbc4.jar"
        try {
            val ret = downloadFileAsBlob(fileURL, "", "")
            println(ret)
        } catch (ex: IOException) {
            ex.printStackTrace()
        }
    }
}