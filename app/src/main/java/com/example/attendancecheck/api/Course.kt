package com.example.attendancecheck.api

import com.google.gson.annotations.SerializedName

data class Course(
    val course_id: Int,
    val course_code: String,
    val course_name: String,
    val lecturer_id: Int,
    val lecturer_name: String,
    val student_count: Int? = null,
    @SerializedName("has_active_qr")
    private val _hasActiveQR: Int = 0,
    val qr_remaining_seconds: Int = 0
) {
    val has_active_qr: Boolean
        get() = _hasActiveQR == 1
} 