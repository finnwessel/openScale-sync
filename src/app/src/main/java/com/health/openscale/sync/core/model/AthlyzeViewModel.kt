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
package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R
import net.openid.appauth.AuthState
import org.json.JSONException
import timber.log.Timber

class AthlyzeViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    private val _athlyzeServer = MutableLiveData<String>(sharedPreferences.getString("athlyze_server", "https://athlyze.de/api/v1/"))
    private val _athlyzeAuthState = MutableLiveData<AuthState?>()

    init {
        val authStateJson = sharedPreferences.getString("athlyze_auth_state", null)
        if (authStateJson != null) {
            try {
                _athlyzeAuthState.value = AuthState.jsonDeserialize(authStateJson)
                Timber.d("[DEBUG] Loaded AuthState: ${_athlyzeAuthState.value?.jsonSerializeString()}")
            } catch (e: JSONException) {
                Timber.d("[DEBUG] Failed to load AuthState: ${e.message}")
                // Handle error
            }
        } else {
            Timber.d("[DEBUG] No saved AuthState found")
        }
    }

    override fun getName(): String {
        return "Athlyze"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_athlyze
    }

    val athlyzeServer: LiveData<String> = _athlyzeServer
    fun setAthlyzeServer(value: String) {
        Timber.d("[DEBUG] setAthlyzeServer: $value")
        this._athlyzeServer.value = value
        sharedPreferences.edit().putString("athlyze_server", value).apply()
    }

    val athlyzeAuthState: LiveData<AuthState?> = _athlyzeAuthState
    fun setAthlyzeAuthState(value: AuthState?) {
        Timber.d("[DEBUG] setAthlyzeAuthState: ${value?.jsonSerializeString()}")
        this._athlyzeAuthState.value = value
        if (value != null) {
            sharedPreferences.edit().putString("athlyze_auth_state", value.jsonSerializeString()).apply()
        } else {
            sharedPreferences.edit().remove("athlyze_auth_state").apply()
        }
    }
}
