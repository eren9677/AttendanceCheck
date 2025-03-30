package com.example.attendancecheck.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    @POST("api/register")
    suspend fun register(@Body registerRequest: RegisterRequest): Response<RegisterResponse>

    @GET("api/courses")
    suspend fun getCourses(@Header("Authorization") token: String): Response<CoursesResponse>
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

data class Course(
    val course_id: Int,
    val course_code: String,
    val course_name: String,
    val lecturer_id: Int
)

data class CoursesResponse(
    val courses: List<Course>
) 