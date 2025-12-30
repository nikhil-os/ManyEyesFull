package com.manyeyes.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object ServiceBuilder {
    fun api(baseUrl: String): ApiService {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .retryOnConnectionFailure(true)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .writeTimeout(java.time.Duration.ofSeconds(10))
            .build()
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        // Force HTTPS on production Render URL to avoid mixed content and proxy issues
        val effective = if (normalized.contains("manyeyes-pxvf.onrender.com") && normalized.startsWith("http://")) {
            normalized.replaceFirst("http://", "https://")
        } else normalized
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(effective)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return retrofit.create(ApiService::class.java)
    }
}
