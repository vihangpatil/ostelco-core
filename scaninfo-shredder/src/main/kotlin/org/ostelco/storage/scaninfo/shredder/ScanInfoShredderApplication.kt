package org.ostelco.storage.scaninfo.shredder


import com.google.cloud.NoCredentials
import com.google.cloud.datastore.*
import com.google.cloud.datastore.StructuredQuery.OrderBy
import com.google.cloud.datastore.testing.LocalDatastoreHelper
import com.google.cloud.http.HttpTransportOptions
import io.dropwizard.Application
import io.dropwizard.Configuration
import io.dropwizard.cli.ConfiguredCommand
import io.dropwizard.setup.Bootstrap
import io.dropwizard.setup.Environment
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.sourceforge.argparse4j.inf.Namespace
import org.ostelco.prime.model.ScanMetadata
import org.ostelco.prime.model.ScanMetadataEnum
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.*
import io.dropwizard.configuration.EnvironmentVariableSubstitutor
import io.dropwizard.configuration.SubstitutingSourceProvider


/**
 * Main entry point, invoke dropwizard application.
 */
fun main(args: Array<String>) = ScanInfoShredderApplication().run(*args)

/**
 * The configuration for Scan Information Shredder module.
 */
class ScanInfoShredderConfig : Configuration() {
    var storeType = "default"
    var namespace = ""
    var deleteScan = false
    var deleteUrl = "https://netverify.com/api/netverify/v2/scans/"
}

/**
 * Main entry point to the scaninfo-shredder API server.
 */
private class ScanInfoShredderApplication : Application<ScanInfoShredderConfig>() {

    override fun initialize(bootstrap: Bootstrap<ScanInfoShredderConfig>) {
        // Enable variable substitution with environment variables
        bootstrap.configurationSourceProvider = SubstitutingSourceProvider(
                bootstrap.configurationSourceProvider,
                EnvironmentVariableSubstitutor(false))
        bootstrap.addCommand(ShredScans())
        bootstrap.addCommand(CacheJars())
    }

    override fun run(
            configuration: ScanInfoShredderConfig,
            environment: Environment) {
    }
}

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
 * Thrown when something really bad is detected and it's necessary to terminate
 * execution immediately.  No cleanup of anything will be done.
 */
private class ScanInfoShredderException : RuntimeException {
    constructor(message: String, ex: Exception?) : super(message, ex)
    constructor(message: String) : super(message)
    constructor(ex: Exception) : super(ex)
}


/**
 * Adapter class that will delete Scan Information from Jumio.
 */
internal class ScanInfoShredder(val config: ScanInfoShredderConfig) {

    private val logger: Logger = LoggerFactory.getLogger(ScanInfoShredder::class.java)

    //val expiryDuration = 1209600000 // 2 Weeks in Milliseconds
    val expiryDuration = 30000 // TODO Remove

    internal lateinit var datastore: Datastore
    internal lateinit var keyFactory: KeyFactory
    private lateinit var filter: StructuredQuery.Filter

    // Used by unit tests
    private lateinit var localDatastoreHelper: LocalDatastoreHelper

    /* Generated by Jumio and can be obtained from the console. */
    private lateinit var apiToken: String
    private lateinit var apiSecret: String

    // Initialize the object, get all the environment variables and initialize the encrypter library.
    fun init(environmentVars: EnvironmentVars) {
        val storeType = config.storeType
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
        logger.info("Config storeType = ${config.storeType}, namespace = ${config.namespace}, deleteScan = ${config.deleteScan} deleteUrl = ${config.deleteUrl} ")
        initDatastore()
    }

    fun cleanup() {
        if (config.storeType == "inmemory-emulator") {
            // Stop the emulator after unit tests.
            localDatastoreHelper.stop()
        }
    }

    // Integration testing helper for Datastore.
    private fun initDatastore() {
        datastore = when (config.storeType) {
            "inmemory-emulator" -> {
                logger.info("Starting with in-memory datastore emulator")
                localDatastoreHelper = LocalDatastoreHelper.create(1.0)
                localDatastoreHelper.start()
                localDatastoreHelper.options
            }
            "emulator" -> {
                // When prime running in GCP by hosted CI/CD, Datastore client library assumes it is running in
                // production and ignore our instruction to connect to the datastore emulator. So, we are explicitly
                // connecting to emulator
                logger.info("Connecting to datastore emulator")
                DatastoreOptions
                        .newBuilder()
                        .setHost("localhost:9090")
                        .setCredentials(NoCredentials.getInstance())
                        .setTransportOptions(HttpTransportOptions.newBuilder().build())
                        .build()
            }
            else -> {
                var optionsBuilder = DatastoreOptions.newBuilder()
                if (!config.namespace.isNullOrEmpty()) {
                    optionsBuilder = optionsBuilder.setNamespace(config.namespace)
                }
                logger.info("Created default instance of datastore client")
                optionsBuilder.setNamespace(config.namespace).build()
            }
        }.service
        keyFactory =  datastore.newKeyFactory().setKind(ScanMetadataEnum.KIND.s)
        val expiryTime:Long = Instant.now().toEpochMilli() - expiryDuration
        filter = StructuredQuery.PropertyFilter.le(ScanMetadataEnum.PROCESSED_TIME.s, expiryTime)
    }

    /**
     * Deletes the scan information from Jumio database.
     */
    private fun deleteScanInformation(vendorScanId: String, baserUrl:String, username: String, password: String): Boolean {
        val url = URL("$baserUrl/$vendorScanId")
        val httpConn = url.openConnection() as HttpURLConnection
        val userpass = "$username:$password"
        val authHeader = "Basic ${Base64.getEncoder().encodeToString(userpass.toByteArray())}"
        httpConn.setRequestProperty("Authorization", authHeader)
        httpConn.setRequestProperty("Accept", "application/json")
        httpConn.setRequestProperty("User-Agent", "ScanInformationStore")
        httpConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        httpConn.setDoOutput(true);
        httpConn.setRequestMethod("DELETE");

        try {
            val responseCode = httpConn.responseCode
            // always check HTTP response code first
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val statusMessage = "$responseCode: ${httpConn.responseMessage}"
                logger.error("Failed to delete ${url.toString()} $statusMessage")
                return false
            }
        } catch (e: IOException) {
            logger.error("Caught exception while trying to delete scan", e)
            return false
        } finally {
            httpConn.disconnect()
        }
        return true
    }

    private fun entitiesToScanMetadata(resultList: QueryResults<Entity>): List<ScanMetadata> {
        val resultScans = ArrayList<ScanMetadata>()
        while (resultList.hasNext()) {
            val entity = resultList.next()
            val metadata = ScanMetadata(
                    id = entity.getString(ScanMetadataEnum.ID.s),
                    scanReference = entity.getString(ScanMetadataEnum.SCAN_REFERENCE.s),
                    countryCode = entity.getString(ScanMetadataEnum.COUNTRY_CODE.s),
                    customerId = entity.getString(ScanMetadataEnum.CUSTOMER_ID.s),
                    processedTime = entity.getLong(ScanMetadataEnum.PROCESSED_TIME.s)
            )
            resultScans.add(metadata)
        }
        return resultScans
    }

    private fun listScans(startCursorString: String?): Pair<List<ScanMetadata>, String?> {
       try {
           var startCursor: Cursor? = null
           if (startCursorString != null && startCursorString != "") {
               startCursor = Cursor.fromUrlSafe(startCursorString)                 // Where we left off
           }
           val query = Query.newEntityQueryBuilder()                               // Build the Query
                    .setKind(ScanMetadataEnum.KIND.s)                               // We only care about ScanMetadata
                    .setLimit(100)                                                  // Only process 100 at a time
                    .setStartCursor(startCursor)                                    // Where we left off
                    .setFilter(filter)                                              // Which are expired
                    .setOrderBy(OrderBy.asc(ScanMetadataEnum.PROCESSED_TIME.s))     // Sorted by "processedTime"
                    .build()
            val resultList = datastore.run(query)
            val resultScans = entitiesToScanMetadata(resultList)                    // Retrieve and convert Entities
            val cursor = resultList.getCursorAfter()                                // Where to start next time
            if (resultScans.size == 100) {                                          // Are we paging? Save Cursor
                val cursorString = cursor!!.toUrlSafe()                             // Cursors are WebSafe
                return Pair(resultScans, cursorString)
            } else {
                return Pair(resultScans, null)
            }
        } catch (e: DatastoreException) {
            logger.error("Caught exception while scanning metadata", e)
            return Pair(ArrayList<ScanMetadata>(), null)
        }
    }

    // Deletes the scan metadata from datastore
    private fun deleteScanMetadata(data: ScanMetadata): Boolean {
        try {
            val keyString = "${data.customerId}-${data.id}"
            datastore.delete(keyFactory.newKey(keyString))
        } catch (e: DatastoreException) {
            logger.error("Caught exception while scanning metadata", e)
            return false
        }
        return true;
    }

    suspend fun shred(): Int {
        var totalItems = 0
        logger.info("Querying Datastore for Scan which are expired")
        val start = System.currentTimeMillis()
        coroutineScope {
            var startCursor: String? = null
            do {
                val scanResult = listScans(startCursor)
                scanResult.first.forEach {
                    launch {
                        if (config.deleteScan) {
                            deleteScanInformation(it.scanReference, config.deleteUrl, apiToken, apiSecret)
                        } else {
                            logger.info("Delete disabled, skipping ${it.scanReference}")
                        }
                        deleteScanMetadata(it)
                    }
                }
                totalItems +=  scanResult.first.size
                startCursor = scanResult.second
            } while(startCursor != null)
        }
        // coroutineScope waits for all children to finish.
        val end = System.currentTimeMillis()
        logger.info("Queries finished in ${(end - start)/1000} seconds")
        return totalItems
    }
}

private class ShredScans : ConfiguredCommand<ScanInfoShredderConfig>(
        "shred",
        "Delete all Scans which are expired") {
    override fun run(bootstrap: Bootstrap<ScanInfoShredderConfig>?, namespace: Namespace?, configuration: ScanInfoShredderConfig?) {

        if (configuration == null) {
            throw ScanInfoShredderException("Configuration is null")
        }


        if (namespace == null) {
            throw ScanInfoShredderException("Namespace from config is null")
        }

        runBlocking {
            val shredder = ScanInfoShredder(configuration)
            shredder.init(EnvironmentVars())
            shredder.shred()
            shredder.cleanup()
        }
    }
}
private class CacheJars : ConfiguredCommand<ScanInfoShredderConfig>(
        "quit",
        "Do nothing, only used to prime caches") {
    override fun run(bootstrap: Bootstrap<ScanInfoShredderConfig>?,
                     namespace: Namespace?,
                     configuration: ScanInfoShredderConfig?) {
        // Doing nothing, as advertised.
    }
}
