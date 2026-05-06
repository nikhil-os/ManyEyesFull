package com.manyeyes.network

import retrofit2.http.*

data class LoginReq(val email: String, val password: String, val deviceName: String, val deviceId: String?)

data class DeviceDto(
    val deviceId: String,
    val deviceName: String,
    val isOnline: Boolean,
    val email: String = "",
    val isRevoked: Boolean = false
)

data class LoginRes(
    val token: String,
    val deviceId: String,
    val devices: List<DeviceDto>,
    val isAdmin: Boolean = false,
    val isRevoked: Boolean = false
)

// Admin: user info with their devices
data class AdminUserDto(
    val email: String,
    val createdAt: String = "",
    val devices: List<DeviceDto> = emptyList()
)

// Device revoked status response
data class DeviceStatusRes(
    val email: String,
    val devices: Map<String, DeviceStatusInfo> = emptyMap()
)

data class DeviceStatusInfo(
    val isRevoked: Boolean = false,
    val deviceName: String = ""
)

interface ApiService {
    @POST("/auth/login")
    suspend fun login(@Body req: LoginReq): LoginRes

    // Register typically doesn't return a token; treat body as empty and rely on HTTP status
    @POST("/auth/register")
    suspend fun register(@Body req: LoginReq): retrofit2.Response<Unit>

    @GET("/devices")
    suspend fun devices(@Header("Authorization") bearer: String): List<DeviceDto>

    @POST("/devices/cleanup")
    suspend fun cleanupDevices(@Header("Authorization") bearer: String): retrofit2.Response<Unit>

    @GET("/devices/status")
    suspend fun getDeviceStatus(@Header("Authorization") bearer: String): DeviceStatusRes

    // Admin endpoints
    @GET("/admin/users")
    suspend fun adminUsers(@Header("Authorization") bearer: String): List<AdminUserDto>

    @GET("/admin/devices")
    suspend fun adminDevices(@Header("Authorization") bearer: String): List<DeviceDto>

    @POST("/admin/revoke")
    suspend fun revokeDevice(@Header("Authorization") bearer: String, @Body body: Map<String, String>): retrofit2.Response<Unit>

    @POST("/admin/restore")
    suspend fun restoreDevice(@Header("Authorization") bearer: String, @Body body: Map<String, String>): retrofit2.Response<Unit>
}
