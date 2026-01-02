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
package com.health.openscale.sync.core.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.AthlyzeViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.AthlyzeSync
import kotlinx.coroutines.launch
import net.openid.appauth.*
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class AthlyzeService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: AthlyzeViewModel = AthlyzeViewModel(sharedPreferences)
    private lateinit var athlyzeSync : AthlyzeSync
    private lateinit var athlyzeRetrofit: Retrofit
    private val authService: AuthorizationService
    private lateinit var authLauncher: ActivityResultLauncher<Intent>

    init {
        val builder = AppAuthConfiguration.Builder()

        // NUR im Debug-Modus HTTP erlauben!

        // 1. ConnectionBuilder überschreiben, um HTTP zu erlauben
        builder.setConnectionBuilder { uri ->
            URL(uri.toString()).openConnection() as HttpURLConnection
        }

        // 2. HTTPS-Check für den Issuer (Keycloak URL) deaktivieren
        builder.setSkipIssuerHttpsCheck(true)


        val appAuthConfig = builder.build()

        // 3. Den Service mit dieser Konfiguration erstellen
        authService = AuthorizationService(context, appAuthConfig)
    }

    override suspend fun init() {
        connectAthlyze()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    override fun registerActivityResultLauncher(activity: ComponentActivity) {
        authLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                val response = AuthorizationResponse.fromIntent(data)
                val ex = AuthorizationException.fromIntent(data)
                
                if (response != null) {
                    viewModel.setAthlyzeAuthState(AuthState(response, ex))
                    exchangeAuthorizationCode(response)
                } else {
                    setErrorMessage("Authorization failed: ${ex?.message}")
                }
            }
        }
    }

    private fun exchangeAuthorizationCode(response: AuthorizationResponse) {
        authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, ex ->
            viewModel.athlyzeAuthState.value?.update(tokenResponse, ex)
            viewModel.setAthlyzeAuthState(viewModel.athlyzeAuthState.value)
            
            if (tokenResponse != null) {
                // Token exchange successful
                viewModel.viewModelScope.launch {
                    connectAthlyze()
                }
            } else {
                setErrorMessage("Token exchange failed: ${ex?.message}")
            }
        }
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return athlyzeSync.fullSync(measurements)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }
    override suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return athlyzeSync.insert(measurement)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun delete(date: Date) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return athlyzeSync.delete(date)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun clear() : SyncResult<Unit>  {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return athlyzeSync.clear()
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return athlyzeSync.update(measurement)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    private fun connectAthlyze() {
        if (viewModel.syncEnabled.value) {
            try {
                val authState = viewModel.athlyzeAuthState.value
                if (authState == null || !authState.isAuthorized) {
                    // Not authorized yet
                    return
                }

                authState.performActionWithFreshTokens(authService) { accessToken, idToken, ex ->
                    if (ex != null) {
                        setErrorMessage("Failed to refresh token: ${ex.message}")
                        return@performActionWithFreshTokens
                    }

                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val newRequest = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer $accessToken")
                            .build()
                        chain.proceed(newRequest)
                    }.build()

                    athlyzeRetrofit = Retrofit.Builder()
                        .client(client)
                        .baseUrl(viewModel.athlyzeServer.value!!)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()

                    athlyzeSync = AthlyzeSync(athlyzeRetrofit)
                    
                    // Verify connection
                    viewModel.viewModelScope.launch {
                        try {
                            val athlyzeApi: AthlyzeSync.AthlyzeApi = athlyzeRetrofit.create(AthlyzeSync.AthlyzeApi::class.java)
                            val athlyzeEntryList = athlyzeApi.entryList()

                            if (athlyzeEntryList.count >= 0) {
                                viewModel.setAllPermissionsGranted(true)
                                viewModel.setConnectAvailable(true)
                                clearErrorMessage()
                                setInfoMessage(context.getString(R.string.athlyze_successful_connected_text))
                            } else {
                                setErrorMessage(context.getString(R.string.athlyze_not_successful_connected_error))
                            }
                        } catch (e: Exception) {
                            setErrorMessage("Connection failed: ${e.message}")
                        }
                    }
                }
            } catch (ex: Exception) {
                setErrorMessage("${ex.message}")
            }
        }
    }

    private fun startAuthorization() {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("http://192.168.178.98:8081/realms/athlyze/protocol/openid-connect/auth"), // Replace with actual auth endpoint
            Uri.parse("http://192.168.178.98:8081/realms/athlyze/protocol/openid-connect/token") // Replace with actual token endpoint
        )

        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            "openscale-sync", // Replace with actual client ID
            ResponseTypeValues.CODE,
            Uri.parse("com.health.openscale.sync:/oauth2callback")
        ).setScope("openid profile email offline_access") // Add offline_access scope
         .build()

        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
        authLauncher.launch(authIntent)
    }

    @Composable
    override fun composeSettings(activity: ComponentActivity) {
        Column (
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            super.composeSettings(activity)

            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val serverNameState by viewModel.athlyzeServer.observeAsState("")

                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = serverNameState,
                    onValueChange = {
                        viewModel.setAthlyzeServer(it)
                    },
                    label = { Text(stringResource(id = R.string.athlyze_server_name_title)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    )
                )
            }
            
            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = {
                    startAuthorization()
                },
                    enabled = viewModel.syncEnabled.value)
                {
                    Text(text = stringResource(id = R.string.athlyze_connect_to_athlyze_button))
                }

                val errorMessage by viewModel.errorMessage.observeAsState()

                if (errorMessage != null && errorMessage != "" && viewModel.syncEnabled.value) {
                    Text("$errorMessage", color = Color.Red)
                }
            }
        }
    }

}
