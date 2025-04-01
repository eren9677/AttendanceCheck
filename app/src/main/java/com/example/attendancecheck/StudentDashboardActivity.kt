package com.example.attendancecheck

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var countdownTimer: CountDownTimer? = null

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
        courseAdapter = CourseAdapter(
            onCourseClick = { course -> handleCourseClick(course) },
            onDeleteClick = { _ -> /* Not used in student view */ },
            onGenerateQRClick = { _ -> /* Not used in student view */ },
            onEnrollClick = { course -> enrollInCourse(course) }
        )
        courseAdapter.setShowingAvailableCourses(true)
        courseAdapter.setLecturerView(false) // Explicitly set to student view
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

    private fun handleCourseClick(course: Course) {
        // If there's an active QR code, show a dialog to scan it
        if (course.has_active_qr) {
            showQRCodeScanDialog(course)
        }
    }

    private fun showQRCodeScanDialog(course: Course) {
        AlertDialog.Builder(this)
            .setTitle("Active QR Code")
            .setMessage("There is an active QR code for ${course.course_code}. Would you like to scan it?")
            .setPositiveButton("Scan") { _, _ ->
                // TODO: Launch QR code scanner activity
                Toast.makeText(this, "QR code scanner will be implemented", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                val token = prefs.getString("token", null)
                
                if (token == null) {
                    Toast.makeText(this@StudentDashboardActivity, 
                        "Please log in again", 
                        Toast.LENGTH_SHORT).show()
                    // Redirect to login
                    startActivity(Intent(this@StudentDashboardActivity, LoginActivity::class.java))
                    finish()
                    return@launch
                }

                val response = apiService.getAllCourses("Bearer $token")
                if (response.isSuccessful) {
                    val courses = response.body()?.courses ?: emptyList()
                    courseAdapter.submitList(courses)
                    updateNoCoursesVisibility(courses)
                    
                    // Start countdown timer for courses with active QR codes
                    startCountdownTimer(courses)
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
                val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                val token = prefs.getString("token", null)
                
                if (token == null) {
                    Toast.makeText(this@StudentDashboardActivity, 
                        "Please log in again", 
                        Toast.LENGTH_SHORT).show()
                    // Redirect to login
                    startActivity(Intent(this@StudentDashboardActivity, LoginActivity::class.java))
                    finish()
                    return@launch
                }

                val response = apiService.getCourses("Bearer $token")
                if (response.isSuccessful) {
                    val courses = response.body()?.courses ?: emptyList()
                    courseAdapter.submitList(courses)
                    updateNoCoursesVisibility(courses)
                    
                    // Start countdown timer for courses with active QR codes
                    startCountdownTimer(courses)
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

    private fun startCountdownTimer(courses: List<Course>) {
        // Cancel existing timer if any
        countdownTimer?.cancel()
        
        // Find the course with the shortest remaining time
        val activeCourse = courses
            .filter { it.has_active_qr && it.qr_remaining_seconds > 0 }
            .minByOrNull { it.qr_remaining_seconds }
        
        if (activeCourse != null) {
            countdownTimer = object : CountDownTimer(activeCourse.qr_remaining_seconds * 1000L, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    // Update the remaining time for the active course
                    courseAdapter.setActiveQRCode(activeCourse.course_id, (millisUntilFinished / 1000).toInt())
                }

                override fun onFinish() {
                    // Clear the active QR code state
                    courseAdapter.clearActiveQRCode()
                }
            }.start()
        }
    }

    private fun enrollInCourse(course: Course) {
        lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                val token = prefs.getString("token", null)
                
                if (token == null) {
                    Toast.makeText(this@StudentDashboardActivity, 
                        "Please log in again", 
                        Toast.LENGTH_SHORT).show()
                    // Redirect to login
                    startActivity(Intent(this@StudentDashboardActivity, LoginActivity::class.java))
                    finish()
                    return@launch
                }

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

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }
} 