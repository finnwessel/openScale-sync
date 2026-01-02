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
                val response: Response<Unit> = athlyzeApi.insert(entry)
                if (!response.isSuccessful) {
                    Timber.d("athlyze ${athlyzeDateFormat.format(measurement.date)} insert response error ${response.errorBody()?.string()}")
                    failureCount++
                }
            } catch (e: Exception) {
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
            }
        }

        if (failureCount > 0) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"$failureCount of ${measurements.size} measurements failed to sync",null)
        } else {
            return SyncResult.Success(Unit)
        }
    }

    suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        try {
            val entry = AthlyzeEntry(
                date = athlyzeDateFormat.format(measurement.date),
                weight = measurement.weight,
                fat = measurement.fat,
                water = measurement.water,
                muscle = measurement.muscle
            )
            val response: Response<Unit> = athlyzeApi.insert(entry)
            if (response.isSuccessful) {
                return SyncResult.Success(Unit)
            } else {
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"athlyze ${athlyzeDateFormat.format(measurement.date)} insert response error ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun delete(date: Date) : SyncResult<Unit> {
        try {
            val athlyzeEntryList = athlyzeApi.getEntry(athlyzeDateFormat.format(date))
            if (athlyzeEntryList.results?.isNotEmpty() == true) {
                val athlyzeId = athlyzeEntryList.results[0].id
                val response: Response<Unit> = athlyzeApi.delete(athlyzeId)
                if (response.isSuccessful) {
                    return SyncResult.Success(Unit)
                } else {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"athlyze delete response error ${response.errorBody()?.string()}}")
                }
            } else {
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"no entry found for date: ${athlyzeDateFormat.format(date)}")
            }
        } catch (e: Exception) {
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun clear() : SyncResult<Unit> {
        try {
            var athlyzeEntryList = athlyzeApi.entryList()

            do {
                athlyzeEntryList.results?.forEach { athlyzeEntry ->
                    val response: Response<Unit> = athlyzeApi.delete(athlyzeEntry.id)
                    if (!response.isSuccessful) {
                        return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"athlyze delete response error ${response.errorBody()?.string()}}")
                    }
                }

                athlyzeEntryList = athlyzeApi.entryList()
            } while (athlyzeEntryList.count != 0L)

            return SyncResult.Success(Unit)
        } catch (e: Exception) {
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        try {
            val atzhlyzeEntryList = athlyzeApi.getEntry(athlyzeDateFormat.format(measurement.date))
            if (atzhlyzeEntryList.results?.isNotEmpty() == true) {
                val atzhlyzeId = atzhlyzeEntryList.results[0].id
                val entry = AthlyzeEntry(
                    date = athlyzeDateFormat.format(measurement.date),
                    weight = measurement.weight,
                    fat = measurement.fat,
                    water = measurement.water,
                    muscle = measurement.muscle
                )
                val response: Response<Unit> = athlyzeApi.update(atzhlyzeId, entry)
                if (response.isSuccessful) {
                    return SyncResult.Success(Unit)
                } else {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"atzhlyze update response error ${response.errorBody()?.string()}}")
                }
            } else {
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"no entry found for date: ${athlyzeDateFormat.format(measurement.date)}")
            }
        } catch (e: Exception) {
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
