package com.example.karmcredapp

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    /**
     * Base URL comes from BuildConfig.API_BASE_URL (build.gradle), NOT from
     * a hardcoded LAN IP in source. Override at build time without editing
     * code:
     *
     *   ./gradlew assembleDebug -PKARM_API_BASE_URL="https://<your-lan-ip>:5000/"
     *
     * Defaults to the Android emulator loopback (10.0.2.2 = host machine).
     * For production this MUST be HTTPS - see README "Security".
     */
    private const val BASE_URL = BuildConfig.API_BASE_URL

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(if (BASE_URL.endsWith("/")) BASE_URL else "$BASE_URL/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
