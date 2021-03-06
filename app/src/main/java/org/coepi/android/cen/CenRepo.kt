package org.coepi.android.cen

import android.os.Handler
import android.util.Log
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.BehaviorSubject.create
import org.coepi.android.system.log.log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Vector
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

interface CenRepo {
     // Periodically generated CEN, to be made available via BLE
    val generatedCen: Observable<Cen>

    // Infection reports fetched periodically from the API
    val reports: Observable<List<CenReport>>

     // Send symptoms report
    fun sendReport(report: CenReport): Completable

    // Store CEN from other device
    fun storeCen(cen: Cen)
}

// CoEpiRepo coordinates local Storage (Room) and network API calls to manage the CoEpi protocol
//  (A) refreshCENAndCENKeys: CENKey generation every 7 days, CEN Generation every 15 minutes  (stored in Room)
//  (B) insertCEN: Storage of observed CENs from other BLE devices
class CenRepoImpl(
    private val cenApi: CENApi,
    private val cenDao: RealmCenDao,
    private val cenkeyDao: RealmCenKeyDao,
    private val cenReportDao: RealmCenReportDao
): CenRepo {

    // ------------------------- CEN Management
    // the latest CENKey (AES in base64 encoded form), loaded from local storage
    private var cenKey: String = ""
    private var cenKeyTimestamp = 0

    // the latest CEN (ByteArray form), generated using cenKey
    override val generatedCen : BehaviorSubject<Cen> = create()

    override val reports : BehaviorSubject<List<CenReport>> = create()

    private var CENKeyLifetimeInSeconds = 7*86400 // every 7 days a new key is generated
    var CENLifetimeInSeconds = 15*60   // every 15 mins a new CEN is generated
    private val periodicCENKeysCheckFrequencyInSeconds = 60*30 // run every 30 mins

    // last time (unix timestamp) the CENKeys were requested
    var lastCENKeysCheck = 0


    init {
        generatedCen.onNext(Cen(ByteArray(0))) // TODO what's this for?

        // load last CENKey + CENKeytimestamp from local storage
        val lastKeys = cenkeyDao.lastCENKeys(1)
        if (lastKeys.isNotEmpty()) {
            val lk = lastKeys[0]
            lk.let {
                cenKey = it.key
                cenKeyTimestamp = it.timestamp
            }
        }

        // Setup regular CENKey refresh + CEN refresh
        //  Production: refresh CEN every 15m, refresh CENKey every 7 days
        //  Testing: refresh CEN every 15s, refresh CENKey every minute
        CENKeyLifetimeInSeconds = 15
        CENLifetimeInSeconds = 60
        refreshCENAndCENKeys()

        // Setup regular CENKeysCheck
        periodicCENKeysCheck()
    }

    private fun refreshCENAndCENKeys() {
        val curTimestamp = (System.currentTimeMillis() / 1000L).toInt()
        if ( ( cenKeyTimestamp == 0 ) || ( roundedTimestamp(curTimestamp) > roundedTimestamp(cenKeyTimestamp) ) ) {
            // generate a new AES Key and store it in local storage
            val secretKey = KeyGenerator.getInstance("AES").generateKey()
            cenKey = android.util.Base64.encodeToString(secretKey.encoded,android.util.Base64.DEFAULT)
            cenKeyTimestamp = curTimestamp
            cenkeyDao.insert(CenKey(cenKey, cenKeyTimestamp))
        }
        generatedCen.onNext(generateCEN(cenKey, curTimestamp))
        Handler().postDelayed({
            refreshCENAndCENKeys()
        }, CENLifetimeInSeconds * 1000L)
    }

    private fun generateCEN(CENKey : String, ts : Int): Cen {
        // decode the base64 encoded key
        val decodedCENKey = android.util.Base64.decode(CENKey,android.util.Base64.DEFAULT)
        // rebuild secretKey using SecretKeySpec
        val secretKey: SecretKey = SecretKeySpec(decodedCENKey, 0, decodedCENKey.size, "AES")
        val cipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return Cen(cipher.doFinal(IntToByteArray(roundedTimestamp(ts))))
    }

    private fun roundedTimestamp(ts : Int) : Int {
        val epoch = ts / CENKeyLifetimeInSeconds
        return epoch * CENKeyLifetimeInSeconds
    }

    // when a peripheral CEN is detected through BLE, it is recorded here
    override fun storeCen(cen: Cen) {
        val c = ReceivedCen(
            cen,
            (System.currentTimeMillis() / 1000L).toInt()
        )
        cenDao.insert(c)
    }

    // ------- Network API Calls: mapping into Server Endpoints via Retrofit
    // 1. Client publicizes report to /cenreport along with 3 CENKeys (base64 encoded)
    private fun postCENReport(report: CenReport): Completable = cenApi.postCENReport(report)

    // 2. Client periodically gets publicized CENKeys alongside symptoms/infections reports from /exposurecheck
    private fun cenkeysCheck(timestamp : Int): Call<List<String>> = cenApi.cenkeysCheck(timestamp)

    // 3. Client fetch reports from /cenreport/<cenKey> (base64 encoded)
    private fun getCenReport(cenKey : String): Call<List<CenReport>> = cenApi.getCenReport(cenKey)

    // doPostSymptoms is called when a ViewModel in the UI sees the user finish a Symptoms Report, the Symptoms + last 3 CENKeys are posted to the server
    override fun sendReport(report: CenReport): Completable =
        postCENReport(report)
    // TODO clarify - CENKeysStr is not used. Is this only to check that we have at least 3 CENKeys?
//        val CENKeysStr = lastCENKeys(3)
//        CENKeysStr?.let {
//            postCENReport(report)
//        }

    // lastCENKeys gets the last few CENKeys used to generate CENs by this device
    fun lastCENKeys(lim : Int) : String? {
        val CENKeys = cenkeyDao.lastCENKeys(lim)
        CENKeys.let {
            if (CENKeys.isNotEmpty()) {
                val CENKeysStrings = CENKeys.map{ k -> k.toString() }
                return CENKeysStrings.joinToString(",")
            }
        }
        return null
    }

    fun periodicCENKeysCheck() {
        val call = cenkeysCheck(lastCENKeysCheck)
        call.enqueue(object :
            Callback<List<String>> {
            override fun onResponse(call: Call<List<String>?>?, response: Response<List<String>>) {
                val statusCode: Int = response.code()
                Log.i("CENApi","${statusCode}");
                if ( statusCode == 200 ) {
                    val r: List<String>? = response.body()
                    r?.let {
                        var keyMatched=Vector<String>()
                        for ( i in it.indices ) {
                            it[i]?.let { key ->
                                val matched = matchCENKey(key, lastCENKeysCheck)
                                if( matched!= null && matched.isNotEmpty() ){
                                    keyMatched.add(key);
                                }
                            }
                        }
                        if( keyMatched.isNotEmpty() ){
                            log.i("You've met a person with symptoms");
                            processMatches(keyMatched);
                        }
                    }
                } else {
                    log.e("periodicCENKeysCheck $statusCode")
                }
            }

            override fun onFailure(call: Call<List<String>?>?, t: Throwable?) {
                // Log error here since request failed
                log.e("periodicCENKeysCheck Failure"+ t?.message)
            }
        })
        Handler().postDelayed({
            periodicCENKeysCheck()  // TODO: worry about tail recursion / call stack depth?
        }, periodicCENKeysCheckFrequencyInSeconds * 1000L)
    }

    // processMatches fetches CENReport
    fun processMatches(matchedCENKeys : Vector<String>?) {
        matchedCENKeys?.let {
            if ( it.size > 0 ) {
                log.i("processMatches MATCH Found")
                for (i in it.indices) {
                    val matchedCENkey = matchedCENKeys[i]
                    val call = getCenReport(matchedCENkey)
                    // TODO: for each match fetch Report data and record in Symptoms
                    // cenReportDao.insert(cenReport)
                    // TODO notify observer on completion of database write
                    // TODO or observe directly database
                    // TODO clarify with Rust lib whether it will store the reports (probably not)
//                    reports.onNext(cenReport())
                }
            }
        }
    }

    // matchCENKey uses a publicized key and finds matches with one database call per key
    //  Not efficient... It would be best if all observed CENs are loaded into memory
    fun matchCENKey(key : String, maxTimestamp : Int) : List<RealmReceivedCen>? {
        // take the last 7 days of timestamps and generate all the possible CENs (e.g. 7 days) TODO: Parallelize this?
        val minTimestamp = maxTimestamp - 7*24* 60
        var possibleCENs = Array<String>(7*24 *(60/CENLifetimeInSeconds)) {i ->
            val ts = maxTimestamp - CENLifetimeInSeconds * i
            val CENBytes = generateCEN(key, ts)
            CENBytes.toString()
        }
        // check if the possibleCENs are in the CEN Table
        return cenDao.matchCENs(minTimestamp, maxTimestamp, possibleCENs)
    }
}
