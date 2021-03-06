package org.coepi.android.cen

import io.realm.kotlin.createObject
import io.realm.kotlin.oneOf
import io.realm.kotlin.where
import org.coepi.android.repo.RealmProvider

class RealmCenReportDao(private val realmProvider: RealmProvider) {
    private val realm get() = realmProvider.realm

    fun all(limit : Int): List<RealmCenReport> =
        realm.where<RealmCenReport>()
            .findAll()

    fun loadAllById(id: Array<String>): List<RealmCenReport> =
        realm.where<RealmCenReport>()
            .oneOf("id", id)
            .findAll()

    fun findByRange(start: Long, end: Long): List<RealmCenReport> =
        realm.where<RealmCenReport>()
            .greaterThanOrEqualTo("timestamp", start)
            .and()
            .lessThanOrEqualTo("timestamp", end)
            .limit(1) // TODO why limit 1?
            .findAll()

    fun insert(report: CenReport) {
        realm.executeTransaction {
            val realmObj = realm.createObject<RealmCenReport>() // Create a new object
            realmObj.id = report.id
            realmObj.report = report.report
            realmObj.keys = report.keys
            realmObj.reportMimeType = report.reportMimeType
            realmObj.date = report.date
            realmObj.isUser = report.isUser
        }
    }

    fun delete(report: CenReport) {
        val results = realm.where<RealmCenReport>()
            .equalTo("id", report.id)
            .findAll()

        realm.executeTransaction {
            results.deleteAllFromRealm()
        }
    }
}
