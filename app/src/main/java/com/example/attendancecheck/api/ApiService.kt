package com.example.attendancecheck.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    @POST("api/register")
    suspend fun register(@Body registerRequest: RegisterRequest): Response<RegisterResponse>
}

data class LoginRequest(
    val university_id: String,
    val password: String
)

data class LoginResponse(
    val message: String,
    val token: String,
    val user: User
)

data class RegisterRequest(
    val university_id: String,
    val password: String,
    val name: String,
    val role: String
)

data class RegisterResponse(
    val message: String
)

data class User(
    val user_id: Int,
    val name: String,
    val university_id: String,
    val role: String
) 