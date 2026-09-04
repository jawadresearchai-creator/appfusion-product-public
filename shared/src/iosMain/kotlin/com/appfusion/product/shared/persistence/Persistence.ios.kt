package com.appfusion.product.shared.persistence

import androidx.room3.Room
import androidx.room3.RoomDatabase

fun documentDatabaseBuilder(path: String): RoomDatabase.Builder<DocumentDomainDatabase> =
    Room.databaseBuilder<DocumentDomainDatabase>(name = path)

fun activityDatabaseBuilder(path: String): RoomDatabase.Builder<ActivityDomainDatabase> =
    Room.databaseBuilder<ActivityDomainDatabase>(name = path)
