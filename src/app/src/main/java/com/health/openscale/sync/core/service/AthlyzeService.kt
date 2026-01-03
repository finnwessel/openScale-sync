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
import timber.log.Timber
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
            Timber.d("[DEBUG] onActivityResult: resultCode=${result.resultCode} data=${result.data}")
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                val response = AuthorizationResponse.fromIntent(data)
                val ex = AuthorizationException.fromIntent(data)
                Timber.d("[DEBUG] AuthorizationResponse: ${response?.jsonSerializeString()} Exception: $ex")
                
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
        Timber.d("[DEBUG] exchangeAuthorizationCode: ${response.jsonSerializeString()}")
        authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, ex ->
            Timber.d("[DEBUG] performTokenRequest result: ${tokenResponse?.jsonSerializeString()} ex: $ex")
            viewModel.athlyzeAuthState.value?.update(tokenResponse, ex)
            viewModel.setAthlyzeAuthState(viewModel.athlyzeAuthState.value)
            
            if (tokenResponse != null) {
                Timber.d("[DEBUG] Token exchange successful. AccessToken: ${tokenResponse.accessToken}")
                // Token exchange successful
                viewModel.viewModelScope.launch {
                    connectAthlyze()
                }
            } else {
                Timber.d("[DEBUG] Token exchange failed: ${ex?.message}")
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
        Timber.d("[DEBUG] connectAthlyze")
        if (viewModel.syncEnabled.value) {
            try {
                val authState = viewModel.athlyzeAuthState.value
                Timber.d("[DEBUG] AuthState: ${authState?.jsonSerializeString()}")
                if (authState == null || !authState.isAuthorized) {
                    Timber.d("[DEBUG] Not authorized")
                    // Not authorized yet
                    return
                }

                authState.performActionWithFreshTokens(authService) { accessToken, idToken, ex ->
                    Timber.d("[DEBUG] performActionWithFreshTokens: accessToken=$accessToken idToken=$idToken ex=$ex")
                    if (ex != null) {
                        Timber.d("[DEBUG] Failed to refresh token: ${ex.message}")
                        setErrorMessage("Failed to refresh token: ${ex.message}")
                        return@performActionWithFreshTokens
                    }

                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val newRequest = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer $accessToken")
                            .build()
                        Timber.d("[DEBUG] Request: ${newRequest.url()} Headers: ${newRequest.headers()}")
                        val response = chain.proceed(newRequest)
                        Timber.d("[DEBUG] Response: ${response.code()} ${response.message()}")
                        response
                    }.build()

                    var url = viewModel.athlyzeServer.value!!
                    if (!url.endsWith("/")) {
                        url += "/"
                    }

                    athlyzeRetrofit = Retrofit.Builder()
                        .client(client)
                        .baseUrl(url)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()

                    athlyzeSync = AthlyzeSync(athlyzeRetrofit)
                    
                    // Verify connection
                    viewModel.viewModelScope.launch {
                        try {
                            val athlyzeApi: AthlyzeSync.AthlyzeApi = athlyzeRetrofit.create(AthlyzeSync.AthlyzeApi::class.java)
                            val athlyzeEntryList = athlyzeApi.entryList()
                            Timber.d("[DEBUG] entryList count: ${athlyzeEntryList.count}")

                            if (athlyzeEntryList.count >= 0) {
                                viewModel.setAllPermissionsGranted(true)
                                viewModel.setConnectAvailable(true)
                                clearErrorMessage()
                                setInfoMessage(context.getString(R.string.athlyze_successful_connected_text))
                            } else {
                                setErrorMessage(context.getString(R.string.athlyze_not_successful_connected_error))
                            }
                        } catch (e: Exception) {
                            Timber.d("[DEBUG] Connection failed: ${e.message}")
                            setErrorMessage("Connection failed: ${e.message}")
                        }
                    }
                }
            } catch (ex: Exception) {
                Timber.d("[DEBUG] connectAthlyze exception: ${ex.message}")
                setErrorMessage("${ex.message}")
            }
        }
    }

    private fun startAuthorization() {
        Timber.d("[DEBUG] startAuthorization")
        val serverUrl = viewModel.athlyzeServer.value
        if (serverUrl.isNullOrEmpty()) {
            setErrorMessage("Server URL is missing")
            return
        }

        viewModel.viewModelScope.launch {
            try {
                var url = viewModel.athlyzeServer.value!!
                if (!url.endsWith("/")) {
                    url += "/"
                }
                // Create a temporary Retrofit instance to fetch settings
                val tempRetrofit = Retrofit.Builder()
                    .baseUrl(url)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val tempSync = AthlyzeSync(tempRetrofit)
                val settingsResult = tempSync.getSettings()

                if (settingsResult is SyncResult.Success) {
                    val keycloakBaseUrl = settingsResult.data.keycloakBaseUrl?.removeSuffix("/")
                    if (keycloakBaseUrl != null) {
                        Timber.d("[DEBUG] Fetched Keycloak Base URL: $keycloakBaseUrl")

                        val serviceConfig = AuthorizationServiceConfiguration(
                            Uri.parse("${keycloakBaseUrl}/realms/athlyze/protocol/openid-connect/auth"),
                            Uri.parse("${keycloakBaseUrl}/realms/athlyze/protocol/openid-connect/token")
                        )

                        val authRequest = AuthorizationRequest.Builder(
                            serviceConfig,
                            "openscale-sync",
                            ResponseTypeValues.CODE,
                            Uri.parse("com.health.openscale.sync:/oauth2callback")
                        ).setScope("openid profile email offline_access")
                            .build()

                        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
                        authLauncher.launch(authIntent)
                    } else {
                        setErrorMessage("Keycloak Base URL not found in settings")
                    }
                } else {
                    setErrorMessage("Failed to fetch settings")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during authorization start")
                setErrorMessage("Error: ${e.message}")
            }
        }
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
