package com.example.attendancecheck.api

data class Course(
    val course_id: Int,
    val course_code: String,
    val course_name: String,
    val lecturer_id: Int,
    val lecturer_name: String,
    val student_count: Int? = null
) 