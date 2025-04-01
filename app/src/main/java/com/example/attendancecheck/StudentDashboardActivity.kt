package com.example.attendancecheck

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.attendancecheck.adapters.CourseAdapter
import com.example.attendancecheck.api.ApiService
import com.example.attendancecheck.api.Course
import com.example.attendancecheck.databinding.ActivityStudentDashboardBinding
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class StudentDashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStudentDashboardBinding
    private lateinit var apiService: ApiService
    private lateinit var courseAdapter: CourseAdapter
    private var isShowingAvailableCourses = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Student Dashboard"

        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)

        // Set up RecyclerView
        courseAdapter = CourseAdapter { course ->
            enrollInCourse(course)
        }
        courseAdapter.setShowingAvailableCourses(true)
        binding.rvCourses.apply {
            layoutManager = LinearLayoutManager(this@StudentDashboardActivity)
            adapter = courseAdapter
        }

        // Set up navigation buttons
        binding.btnAvailableCourses.setOnClickListener {
            if (!isShowingAvailableCourses) {
                isShowingAvailableCourses = true
                updateButtonStates()
                loadAvailableCourses()
            }
        }

        binding.btnMyCourses.setOnClickListener {
            if (isShowingAvailableCourses) {
                isShowingAvailableCourses = false
                updateButtonStates()
                loadEnrolledCourses()
            }
        }

        // Load initial view (Available Courses)
        loadAvailableCourses()
    }

    private fun updateButtonStates() {
        binding.btnAvailableCourses.isSelected = isShowingAvailableCourses
        binding.btnMyCourses.isSelected = !isShowingAvailableCourses
        courseAdapter.setShowingAvailableCourses(isShowingAvailableCourses)
    }

    private fun updateNoCoursesVisibility(courses: List<Course>) {
        binding.tvNoCourses.visibility = if (courses.isEmpty()) View.VISIBLE else View.GONE
        binding.rvCourses.visibility = if (courses.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadAvailableCourses() {
        lifecycleScope.launch {
            try {
                val token = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                    .getString("token", null) ?: throw IllegalStateException("No token found")

                val response = apiService.getAllCourses("Bearer $token")
                if (response.isSuccessful) {
                    val courses = response.body()?.courses ?: emptyList()
                    courseAdapter.submitList(courses)
                    updateNoCoursesVisibility(courses)
                } else {
                    Toast.makeText(this@StudentDashboardActivity, 
                        "Failed to load available courses: ${response.message()}", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StudentDashboardActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadEnrolledCourses() {
        lifecycleScope.launch {
            try {
                val token = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                    .getString("token", null) ?: throw IllegalStateException("No token found")

                val response = apiService.getCourses("Bearer $token")
                if (response.isSuccessful) {
                    val courses = response.body()?.courses ?: emptyList()
                    courseAdapter.submitList(courses)
                    updateNoCoursesVisibility(courses)
                } else {
                    Toast.makeText(this@StudentDashboardActivity, 
                        "Failed to load enrolled courses: ${response.message()}", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StudentDashboardActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enrollInCourse(course: Course) {
        lifecycleScope.launch {
            try {
                val token = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                    .getString("token", null) ?: throw IllegalStateException("No token found")

                val response = apiService.enrollInCourse(
                    "Bearer $token",
                    mapOf("course_id" to course.course_id)
                )

                if (response.isSuccessful) {
                    Toast.makeText(this@StudentDashboardActivity, 
                        "Successfully enrolled in ${course.course_code}", 
                        Toast.LENGTH_SHORT).show()
                    loadAvailableCourses() // Reload available courses
                    loadEnrolledCourses() // Reload enrolled courses
                } else {
                    Toast.makeText(this@StudentDashboardActivity, 
                        "Failed to enroll: ${response.message()}", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StudentDashboardActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
} 