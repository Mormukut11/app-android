package org.coepi.android.cen

import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import org.coepi.android.ble.BleManager
import org.coepi.android.ble.BlePreconditions
import org.coepi.android.ble.BlePreconditionsNotifier
import org.coepi.android.ble.Uuids
import org.coepi.android.ble.covidwatch.ScannedData
import org.coepi.android.system.log.log
import java.util.UUID

class CenManager(
    private val blePreconditions: BlePreconditionsNotifier,
    private val bleManager: BleManager,
    private val cenRepo: CenRepo
) {
    private val disposables = CompositeDisposable()

    fun start() {
        initServiceWhenBleIsEnabled()
        observeCen()
        observeScanner()
    }

    private fun initServiceWhenBleIsEnabled() {
        disposables += Observables.combineLatest(
            blePreconditions.bleEnabled,
            // Take the first CEN, needed to start the service
            cenRepo.CEN
        )
        .take(1)
        .subscribeBy(onNext = { (_, firstCen) ->
            bleManager.startService(firstCen.toString()) // TODO review String <-> ByteArray
            log.i("BlePreconditions met - BLE manager started")
        }, onError = {
            log.i("Error enabling bluetooth: $it")
        })
    }

    /**
     * Sends CEN to advertiser when it's changed in DB
     */
    private fun observeCen() {
        disposables += cenRepo.CEN.subscribeBy (onNext = { cen ->
            // ServiceData holds Android Contact Event Number (CEN) that the Android peripheral is advertising
            val cenString = cen.toString()

            // TODO is check really needed? If yes, either add flag to advertiser or expose state and use here
//            if (started) {
            bleManager.stopAdvertiser()
//            }

            if (cen != null) {
                bleManager.startAdvertiser(cenString)
            }
        }, onError = {
            log.i("Error observing CEN: $it")
        })
    }

    private fun observeScanner() {
        disposables += bleManager.scanObservable
            .subscribeBy(onNext = {
                handleScannedData(it)
            }, onError = {
                log.e("Error scanning: $it")
            })
    }

    /**
     * Inserts scanned CENs in DB
     */
    private fun handleScannedData(data: ScannedData) {
        for (i in data.serviceUuids.indices) {
            val serviceUuid = data.serviceUuids[i]

            if (serviceUuid.isCoepi()) { // TODO move this filtering to BLE classes
                val serviceData = data.serviceData
                // *************** The ServiceData IS WHERE WE TAKE THE ANDROID CEN that the Android peripheral is advertising and we record it in Contacts
                // TODO make service data return the actual service data
                log.i("Discovered CoEpi with ServiceData: $serviceUuid $serviceData")
                cenRepo.insertCEN(serviceData)

            } else {
                // TODO review this. Seems weird.
                val x = Uuids.service.toString()
                val serviceData = data.serviceData
                log.d("Discovered non-CoEpi Service UUID: $x $serviceData")
                cenRepo.insertCEN(serviceData)
            }
        }
    }

    private fun UUID.isCoepi() =
        Uuids.service.toString() == toString()
}
