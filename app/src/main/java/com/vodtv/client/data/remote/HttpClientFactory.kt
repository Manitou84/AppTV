package com.vodtv.client.data.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

object HttpClientFactory {

    /** Json permissivo condiviso: i backend di scraping cambiano spesso formato. */
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun create(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // get_links fa scraping lato server: può essere lento
            requestTimeoutMillis = 45_000
            socketTimeoutMillis = 45_000
        }

        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d("VodHttp", message)
                }
            }
        }

        defaultRequest {
            headers.append(HttpHeaders.Accept, "application/json")
        }

        engine {
            config {
                followRedirects(true)
                retryOnConnectionFailure(true)
            }
        }
    }
}
