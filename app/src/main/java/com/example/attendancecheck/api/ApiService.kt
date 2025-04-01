package com.example.attendancecheck.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    @POST("api/register")
    suspend fun register(@Body registerRequest: RegisterRequest): Response<RegisterResponse>

    @GET("api/courses")
    suspend fun getCourses(@Header("Authorization") token: String): Response<CoursesResponse>

    @GET("api/courses/all")
    suspend fun getAllCourses(@Header("Authorization") token: String): Response<CoursesResponse>

    @POST("api/courses")
    suspend fun createCourse(
        @Header("Authorization") token: String,
        @Body courseData: Map<String, String>
    ): Response<Any>

    @POST("api/enrollments")
    suspend fun enrollInCourse(
        @Header("Authorization") token: String,
        @Body enrollmentData: Map<String, Int>
    ): Response<Any>

    @DELETE("api/courses/{course_id}")
    suspend fun deleteCourse(
        @Header("Authorization") token: String,
        @Path("course_id") courseId: Int
    ): Response<Any>
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

data class CoursesResponse(
    val courses: List<Course>
) 