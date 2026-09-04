package com.appfusion.product.shared.persistence

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

fun documentDatabaseBuilder(
    context: Context,
    path: String,
): RoomDatabase.Builder<DocumentDomainDatabase> =
    Room.databaseBuilder<DocumentDomainDatabase>(context.applicationContext, path)

fun activityDatabaseBuilder(
    context: Context,
    path: String,
): RoomDatabase.Builder<ActivityDomainDatabase> =
    Room.databaseBuilder<ActivityDomainDatabase>(context.applicationContext, path)
