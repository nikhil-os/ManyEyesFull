package com.manyeyes.network

import retrofit2.http.*

data class LoginReq(val email: String, val password: String, val deviceName: String, val deviceId: String?)

data class DeviceDto(val deviceId: String, val deviceName: String, val isOnline: Boolean)

data class LoginRes(val token: String, val deviceId: String, val devices: List<DeviceDto>)

interface ApiService {
    @POST("/auth/login")
    suspend fun login(@Body req: LoginReq): LoginRes

    // Register typically doesn't return a token; treat body as empty and rely on HTTP status
    @POST("/auth/register")
    suspend fun register(@Body req: LoginReq): retrofit2.Response<Unit>

    @GET("/devices")
    suspend fun devices(@Header("Authorization") bearer: String): List<DeviceDto>
}
