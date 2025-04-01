package com.example.attendancecheck

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.attendancecheck.api.ApiService
import com.example.attendancecheck.databinding.ActivityAddCourseBinding
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AddCourseActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAddCourseBinding
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddCourseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Add New Course"

        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)

        // Set up Add Course button
        binding.btnAddCourse.setOnClickListener {
            val courseCode = binding.etCourseCode.text.toString().trim()
            val courseName = binding.etCourseName.text.toString().trim()

            if (validateInputs(courseCode, courseName)) {
                addCourse(courseCode, courseName)
            }
        }
    }

    private fun validateInputs(courseCode: String, courseName: String): Boolean {
        if (courseCode.isEmpty()) {
            binding.etCourseCode.error = "Course code is required"
            return false
        }

        if (courseName.isEmpty()) {
            binding.etCourseName.error = "Course name is required"
            return false
        }

        return true
    }

    private fun addCourse(courseCode: String, courseName: String) {
        lifecycleScope.launch {
            try {
                val token = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                    .getString("token", null) ?: throw IllegalStateException("No token found")

                val response = apiService.createCourse(
                    "Bearer $token",
                    mapOf(
                        "course_code" to courseCode,
                        "course_name" to courseName
                    )
                )

                if (response.isSuccessful) {
                    Toast.makeText(this@AddCourseActivity, 
                        "Course added successfully", 
                        Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@AddCourseActivity, 
                        "Failed to add course: ${response.message()}", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AddCourseActivity, 
                    "Error: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
} 