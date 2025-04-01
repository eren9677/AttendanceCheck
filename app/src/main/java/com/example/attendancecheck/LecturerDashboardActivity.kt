package com.example.attendancecheck

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.attendancecheck.adapters.CourseAdapter
import com.example.attendancecheck.api.ApiService
import com.example.attendancecheck.api.Course
import com.example.attendancecheck.databinding.ActivityLecturerDashboardBinding
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LecturerDashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLecturerDashboardBinding
    private lateinit var apiService: ApiService
    private lateinit var courseAdapter: CourseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLecturerDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Lecturer Dashboard"

        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)

        // Set up RecyclerView
        courseAdapter = CourseAdapter(
            onDeleteClick = { course -> showDeleteConfirmationDialog(course) }
        )
        courseAdapter.setShowingAvailableCourses(false)
        courseAdapter.setLecturerView(true)
        binding.rvCourses.apply {
            layoutManager = LinearLayoutManager(this@LecturerDashboardActivity)
            adapter = courseAdapter
        }

        // Set up Add Course button
        binding.btnAddCourse.setOnClickListener {
            startActivity(Intent(this, AddCourseActivity::class.java))
        }

        // Load courses
        loadCourses()
    }

    private fun showDeleteConfirmationDialog(course: Course) {
        AlertDialog.Builder(this)
            .setTitle("Delete Course")
            .setMessage("Are you sure you want to delete '${course.course_name}'? This will unenroll all students.")
            .setPositiveButton("Delete") { _, _ ->
                deleteCourse(course)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCourse(course: Course) {
        lifecycleScope.launch {
            try {
                val token = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                    .getString("token", null) ?: throw IllegalStateException("No token found")

                val response = apiService.deleteCourse("Bearer $token", course.course_id)
                if (response.isSuccessful) {
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Course deleted successfully", 
                        Toast.LENGTH_SHORT).show()
                    loadCourses() // Reload the courses list
                } else {
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Failed to delete course: ${response.message()}", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LecturerDashboardActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadCourses() {
        lifecycleScope.launch {
            try {
                val token = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                    .getString("token", null) ?: throw IllegalStateException("No token found")

                val response = apiService.getCourses("Bearer $token")
                if (response.isSuccessful) {
                    val courses = response.body()?.courses ?: emptyList()
                    courseAdapter.submitList(courses)
                } else {
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Failed to load courses: ${response.message()}", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LecturerDashboardActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadCourses() // Reload courses when returning from AddCourseActivity
    }
} 