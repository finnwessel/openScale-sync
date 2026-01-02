/*
 *  Copyright (C) 2025  olie.xdev <olie.xdev@googlemail.com>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package com.health.openscale.sync.core.sync

import com.google.gson.annotations.SerializedName
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

class AthlyzeSync(private val athlyzeRetrofit: Retrofit) : SyncInterface() {
    private val athlyzeApi : AthlyzeApi = athlyzeRetrofit.create(AthlyzeApi::class.java)
    private val athlyzeDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").apply { timeZone = TimeZone.getDefault() }

    suspend fun fullSync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> {
        Timber.d("[DEBUG] fullSync: measurements count=${measurements.size}")
        var failureCount = 0

        measurements.forEach { measurement ->
            try {
                val entry = AthlyzeEntry(
                    date = athlyzeDateFormat.format(measurement.date),
                    weight = measurement.weight,
                    fat = measurement.fat,
                    water = measurement.water,
                    muscle = measurement.muscle
                )
                Timber.d("[DEBUG] fullSync: inserting entry=$entry")
                val response: Response<Unit> = athlyzeApi.insert(entry)
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    Timber.d("[DEBUG] fullSync: athlyze ${athlyzeDateFormat.format(measurement.date)} insert response error $errorBody code=${response.code()}")
                    failureCount++
                } else {
                    Timber.d("[DEBUG] fullSync: insert successful")
                }
            } catch (e: Exception) {
                Timber.d("[DEBUG] fullSync: exception=${e.message}")
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
            }
        }

        if (failureCount > 0) {
            Timber.d("[DEBUG] fullSync: finished with $failureCount failures")
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"$failureCount of ${measurements.size} measurements failed to sync",null)
        } else {
            Timber.d("[DEBUG] fullSync: finished successfully")
            return SyncResult.Success(Unit)
        }
    }

    suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        Timber.d("[DEBUG] insert: measurement=$measurement")
        try {
            val entry = AthlyzeEntry(
                date = athlyzeDateFormat.format(measurement.date),
                weight = measurement.weight,
                fat = measurement.fat,
                water = measurement.water,
                muscle = measurement.muscle
            )
            Timber.d("[DEBUG] insert: entry=$entry")
            val response: Response<Unit> = athlyzeApi.insert(entry)
            if (response.isSuccessful) {
                Timber.d("[DEBUG] insert: successful")
                return SyncResult.Success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                Timber.d("[DEBUG] insert: failed code=${response.code()} error=$errorBody")
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"athlyze ${athlyzeDateFormat.format(measurement.date)} insert response error $errorBody")
            }
        } catch (e: Exception) {
            Timber.d("[DEBUG] insert: exception=${e.message}")
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun delete(date: Date) : SyncResult<Unit> {
        Timber.d("[DEBUG] delete: date=$date")
        try {
            val dateStr = athlyzeDateFormat.format(date)
            Timber.d("[DEBUG] delete: searching for date=$dateStr")
            val athlyzeEntryList = athlyzeApi.getEntry(dateStr)
            Timber.d("[DEBUG] delete: found ${athlyzeEntryList.results?.size} entries")
            
            if (athlyzeEntryList.results?.isNotEmpty() == true) {
                val athlyzeId = athlyzeEntryList.results[0].id
                Timber.d("[DEBUG] delete: deleting id=$athlyzeId")
                val response: Response<Unit> = athlyzeApi.delete(athlyzeId)
                if (response.isSuccessful) {
                    Timber.d("[DEBUG] delete: successful")
                    return SyncResult.Success(Unit)
                } else {
                    val errorBody = response.errorBody()?.string()
                    Timber.d("[DEBUG] delete: failed code=${response.code()} error=$errorBody")
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"athlyze delete response error $errorBody}")
                }
            } else {
                Timber.d("[DEBUG] delete: no entry found")
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"no entry found for date: ${athlyzeDateFormat.format(date)}")
            }
        } catch (e: Exception) {
            Timber.d("[DEBUG] delete: exception=${e.message}")
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun clear() : SyncResult<Unit> {
        Timber.d("[DEBUG] clear: starting")
        try {
            var athlyzeEntryList = athlyzeApi.entryList()
            Timber.d("[DEBUG] clear: initial count=${athlyzeEntryList.count}")

            do {
                athlyzeEntryList.results?.forEach { athlyzeEntry ->
                    Timber.d("[DEBUG] clear: deleting id=${athlyzeEntry.id}")
                    val response: Response<Unit> = athlyzeApi.delete(athlyzeEntry.id)
                    if (!response.isSuccessful) {
                        val errorBody = response.errorBody()?.string()
                        Timber.d("[DEBUG] clear: failed to delete id=${athlyzeEntry.id} code=${response.code()} error=$errorBody")
                        return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"athlyze delete response error $errorBody}")
                    }
                }

                athlyzeEntryList = athlyzeApi.entryList()
                Timber.d("[DEBUG] clear: remaining count=${athlyzeEntryList.count}")
            } while (athlyzeEntryList.count != 0L)

            Timber.d("[DEBUG] clear: successful")
            return SyncResult.Success(Unit)
        } catch (e: Exception) {
            Timber.d("[DEBUG] clear: exception=${e.message}")
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        Timber.d("[DEBUG] update: measurement=$measurement")
        try {
            val dateStr = athlyzeDateFormat.format(measurement.date)
            Timber.d("[DEBUG] update: searching for date=$dateStr")
            val atzhlyzeEntryList = athlyzeApi.getEntry(dateStr)
            
            if (atzhlyzeEntryList.results?.isNotEmpty() == true) {
                val atzhlyzeId = atzhlyzeEntryList.results[0].id
                val entry = AthlyzeEntry(
                    date = dateStr,
                    weight = measurement.weight,
                    fat = measurement.fat,
                    water = measurement.water,
                    muscle = measurement.muscle
                )
                Timber.d("[DEBUG] update: updating id=$atzhlyzeId with entry=$entry")
                val response: Response<Unit> = athlyzeApi.update(atzhlyzeId, entry)
                if (response.isSuccessful) {
                    Timber.d("[DEBUG] update: successful")
                    return SyncResult.Success(Unit)
                } else {
                    val errorBody = response.errorBody()?.string()
                    Timber.d("[DEBUG] update: failed code=${response.code()} error=$errorBody")
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"atzhlyze update response error $errorBody}")
                }
            } else {
                Timber.d("[DEBUG] update: no entry found")
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"no entry found for date: ${athlyzeDateFormat.format(measurement.date)}")
            }
        } catch (e: Exception) {
            Timber.d("[DEBUG] update: exception=${e.message}")
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    interface AthlyzeApi {
        @GET("measurements")
        suspend fun entryList(): AthlyzeEntryList

        @GET("measurements/get-by-date")
        suspend fun getEntry(@Query("date") date: String): AthlyzeEntryList

        @POST("measurements")
        suspend fun insert(@Body entry: AthlyzeEntry): Response<Unit>

        @PUT("measurements/{id}")
        suspend fun update(
            @Path("id") id: Long,
            @Body entry: AthlyzeEntry
        ): Response<Unit>

        @DELETE("measurements/{id}")
        suspend fun delete(@Path("id") id: Long) : Response<Unit>
    }

    data class AthlyzeEntryList(
        @SerializedName("count")
        val count: Long = -1,
        @SerializedName("next")
        val next: String? = null,
        @SerializedName("previous")
        val previous: String? = null,
        @SerializedName("results")
        val results: List<AthlyzeEntry>? = null
    )

    data class AthlyzeEntry(
        @SerializedName("id")
        val id: Long = 0,
        @SerializedName("date")
        val date: String? = null,
        @SerializedName("weight")
        val weight: Float = 0f,
        @SerializedName("fat")
        val fat: Float = 0f,
        @SerializedName("water")
        val water: Float = 0f,
        @SerializedName("muscle")
        val muscle: Float = 0f
    )
}
