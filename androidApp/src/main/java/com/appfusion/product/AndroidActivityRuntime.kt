package com.appfusion.product

import android.content.Context
import com.appfusion.product.shared.activity.ActivityCadenceRepository
import com.appfusion.product.shared.activity.CadenceRule
import com.appfusion.product.shared.persistence.activityDatabaseBuilder
import com.appfusion.product.shared.persistence.buildActivityDatabase
import java.time.ZoneId
import java.util.UUID

/** Uses the accepted activity database; never opens or replaces the document database. */
class AndroidActivityRuntime(context: Context) {
    private val database = buildActivityDatabase(activityDatabaseBuilder(context, "appfusion-activities.db"))
    private val repository = ActivityCadenceRepository(database.records())

    suspend fun create(title: String, days: Int, minuteOfDay: Int, zone: String, followDevice: Boolean) {
        repository.create("activity-${UUID.randomUUID()}", title,
            CadenceRule(days, minuteOfDay, zone, followDevice), System.currentTimeMillis())
    }

    suspend fun complete(id: String, eventId: String) = repository.complete(id, eventId, System.currentTimeMillis())

    suspend fun snapshot() = repository.snapshot(System.currentTimeMillis(), ZoneId.systemDefault().id)

    suspend fun history(id: String) = repository.history(id)
}
