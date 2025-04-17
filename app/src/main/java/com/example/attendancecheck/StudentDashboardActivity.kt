package com.example.attendancecheck

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.Menu
import android.view.MenuItem
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
    
    // Set to store attended course IDs for persistence
    private val attendedCourseIds = mutableSetOf<Int>()
    private val PREFS_NAME = "AttendanceCheck"
    private val ATTENDED_COURSES_KEY_PREFIX = "attended_courses_user_"
    private var currentUserId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Student Dashboard"

        // Get current user ID from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentUserId = prefs.getInt("user_id", -1)
        
        if (currentUserId == -1) {
            // User ID not found, redirect to login
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)

        // Load saved attended courses for this specific user
        loadAttendedCourses()
        
        // Set up RecyclerView
        courseAdapter = CourseAdapter(
            onCourseClick = { course -> handleCourseClick(course) },
            onDeleteClick = { _ -> /* Not used in student view */ },
            onGenerateQRClick = { _ -> /* Not used in student view */ },
            onShowQRClick = { _ -> /* Not used in student view */ },
            onEnrollClick = { course -> enrollInCourse(course) },
            onCheckAttendanceClick = { _ -> /* Not used in student view */ }
        )
        courseAdapter.setShowingAvailableCourses(true)
        courseAdapter.setLecturerView(false) // Explicitly set to student view
        
        // Mark previously attended courses
        attendedCourseIds.forEach { courseId ->
            courseAdapter.markCourseAsAttended(courseId)
        }
        
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
    
    /**
     * Save attended courses to SharedPreferences with user-specific key
     */
    private fun saveAttendedCourses() {
        if (currentUserId == -1) return // Safety check
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        
        // User-specific key for attended courses
        val attendedCoursesKey = ATTENDED_COURSES_KEY_PREFIX + currentUserId
        
        // Convert set to comma-separated string 
        val courseIdsString = attendedCourseIds.joinToString(",")
        editor.putString(attendedCoursesKey, courseIdsString)
        editor.apply()
        
        // Log for debugging
        Log.d("StudentDashboard", "Saved attended courses for user $currentUserId: $courseIdsString")
    }
    
    /**
     * Load attended courses from SharedPreferences using user-specific key
     */
    private fun loadAttendedCourses() {
        if (currentUserId == -1) return // Safety check
        
        // Clear any existing data first
        attendedCourseIds.clear()
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        // User-specific key for attended courses
        val attendedCoursesKey = ATTENDED_COURSES_KEY_PREFIX + currentUserId
        val courseIdsString = prefs.getString(attendedCoursesKey, "")
        
        if (!courseIdsString.isNullOrEmpty()) {
            // Convert comma-separated string to set of integers
            courseIdsString.split(",").forEach { idStr ->
                try {
                    val id = idStr.toInt()
                    attendedCourseIds.add(id)
                } catch (e: NumberFormatException) {
                    // Ignore invalid entries
                }
            }
        }
        
        // Log for debugging
        Log.d("StudentDashboard", "Loaded attended courses for user $currentUserId: ${attendedCourseIds.joinToString()}")
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
                // Launch QR code scanner activity
                val intent = Intent(this, QRScannerActivity::class.java).apply {
                    putExtra("COURSE_ID", course.course_id)
                    putExtra("COURSE_NAME", course.course_name)
                    putExtra("COURSE_CODE", course.course_code)
                }
                startActivityForResult(intent, QRScannerActivity.REQUEST_CODE_SCAN)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateButtonStates() {
        binding.btnAvailableCourses.isSelected = isShowingAvailableCourses
        binding.btnMyCourses.isSelected = !isShowingAvailableCourses
        courseAdapter.setShowingAvailableCourses(isShowingAvailableCourses)
        
        // If we're switching to available courses view, clear any active QR code UI
        if (isShowingAvailableCourses) {
            clearEnrolledCoursesUi()
        }
    }
    
    /**
     * Clear any UI elements related to enrolled courses when switching views
     */
    private fun clearEnrolledCoursesUi() {
        // Cancel any active countdowns when moving to available courses view
        countdownTimer?.cancel()
        countdownTimer = null
        
        // Make sure no active course is shown in available courses view
        courseAdapter.clearActiveQRCode()
        
        Log.d("StudentDashboard", "Cleared enrolled courses UI elements")
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
                    
                    // For available courses, we should NOT start countdown timers
                    // as these are courses the student is not enrolled in yet
                    Log.d("StudentDashboard", "Loaded ${courses.size} available courses, not showing QR expiration for these")
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
                    
                    // Only start countdown timer for enrolled courses
                    Log.d("StudentDashboard", "Loaded ${courses.size} enrolled courses, showing QR expiration for these")
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
        
        // Only start timers for enrolled courses with active QR codes
        // This method should only be called from loadEnrolledCourses()
        if (isShowingAvailableCourses) {
            Log.d("StudentDashboard", "Not starting countdown timer because we're in available courses view")
            return
        }
        
        // Find the course with the shortest remaining time
        val activeCourse = courses
            .filter { it.has_active_qr && it.qr_remaining_seconds > 0 }
            .minByOrNull { it.qr_remaining_seconds }
        
        if (activeCourse != null) {
            Log.d("StudentDashboard", "Starting countdown timer for course: ${activeCourse.course_code} (${activeCourse.course_id}), ${activeCourse.qr_remaining_seconds}s remaining")
            
            countdownTimer = object : CountDownTimer(activeCourse.qr_remaining_seconds * 1000L, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    // Update the remaining time for the active course
                    val seconds = (millisUntilFinished / 1000).toInt()
                    courseAdapter.setActiveQRCode(activeCourse.course_id, seconds)
                    
                    if (seconds % 10 == 0) { // Log every 10 seconds to avoid too much output
                        Log.d("StudentDashboard", "QR countdown for course ${activeCourse.course_code}: ${seconds}s remaining")
                    }
                }

                override fun onFinish() {
                    // Clear the active QR code state
                    Log.d("StudentDashboard", "QR countdown finished for course ${activeCourse.course_code}")
                    courseAdapter.clearActiveQRCode(activeCourse.course_id)
                }
            }.start()
        } else {
            Log.d("StudentDashboard", "No active QR codes found among enrolled courses")
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == QRScannerActivity.REQUEST_CODE_SCAN && resultCode == RESULT_OK) {
            // Get the course ID from the data
            val courseId = data?.getIntExtra("COURSE_ID", -1) ?: -1
            
            if (courseId != -1) {
                Log.d("StudentDashboard", "QR scan successful for course ID $courseId by user $currentUserId")
                
                // Mark this course as attended
                courseAdapter.markCourseAsAttended(courseId)
                
                // Add to our persistent set and save
                attendedCourseIds.add(courseId)
                saveAttendedCourses()
                
                // Show success message
                Toast.makeText(this, "Attendance recorded successfully", Toast.LENGTH_SHORT).show()
            } else {
                Log.e("StudentDashboard", "Invalid course ID received from QR scanner")
            }
            
            // Reload the courses after successful scan
            if (isShowingAvailableCourses) {
                loadAvailableCourses()
            } else {
                loadEnrolledCourses()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save attended courses when activity is paused
        saveAttendedCourses()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any active countdowns
        countdownTimer?.cancel()
        
        // Clear the adapter state to avoid issues if another user logs in
        if (::courseAdapter.isInitialized) {
            courseAdapter.clearAttendedCourses()
        }
        
        Log.d("StudentDashboard", "Activity destroyed, cleared adapter state")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dashboard, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun logout() {
        // Show confirmation dialog
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                // Clear user data
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit()
                    .remove("token")
                    .remove("user_role")
                    .remove("user_id")
                    .remove("user_name")
                    .remove("university_id")
                    .apply()
                
                // Clear in-memory data
                attendedCourseIds.clear()
                
                // Clear adapter state
                if (::courseAdapter.isInitialized) {
                    courseAdapter.clearAttendedCourses()
                }
                
                // Return to login screen
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                
                Log.d("StudentDashboard", "User $currentUserId logged out successfully")
            }
            .setNegativeButton("No", null)
            .show()
    }
} 