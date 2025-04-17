package com.example.attendancecheck

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.attendancecheck.adapters.CourseAdapter
import com.example.attendancecheck.api.ApiService
import com.example.attendancecheck.api.Course
import com.example.attendancecheck.api.QRCodeRequest
import com.example.attendancecheck.api.QRCodeResponse
import com.example.attendancecheck.databinding.ActivityLecturerDashboardBinding
import com.example.attendancecheck.databinding.DialogQrTimeSelectionBinding
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*

class LecturerDashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLecturerDashboardBinding
    private lateinit var apiService: ApiService
    private lateinit var courseAdapter: CourseAdapter
    private var countdownTimer: CountDownTimer? = null
    private var currentUserId: Int = -1
    private val PREFS_NAME = "AttendanceCheck"
    
    // Store current active QR code data
    private var activeQRData: Map<Int, QRCodeData> = mutableMapOf()

    // Data class to store QR code information
    data class QRCodeData(
        val qrImage: String,
        val expiresAt: String,
        val remainingSeconds: Int,
        val courseId: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLecturerDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Lecturer Dashboard"

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

        // Set up RecyclerView
        setupCourseAdapter()
        courseAdapter.setShowingAvailableCourses(false)
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
        
        Log.d("LecturerDashboard", "Activity created for user ID: $currentUserId")
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
                
                // Clear adapter state
                if (::courseAdapter.isInitialized) {
                    courseAdapter.clearAttendedCourses()
                }
                
                // Return to login screen
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                
                Log.d("LecturerDashboard", "User $currentUserId logged out successfully")
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun setupCourseAdapter() {
        courseAdapter = CourseAdapter(
            onCourseClick = { course -> showCourseDetails(course) },
            onDeleteClick = { course -> showDeleteConfirmationDialog(course) },
            onGenerateQRClick = { course -> generateQRCode(course) },
            onShowQRClick = { course -> showStoredQRCode(course) },
            onEnrollClick = { /* Not used in lecturer view */ },
            onCheckAttendanceClick = { course -> openAttendanceReport(course) }
        )
        courseAdapter.setLecturerView(true)
    }

    private fun showQRTimeSelectionDialog(onTimeSelected: (Int) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_time_selection, null)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekBar)
        val tvSelectedMinutes = dialogView.findViewById<TextView>(R.id.tvSelectedMinutes)

        // Update text when seekbar changes
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1 // Add 1 because progress starts at 0
                tvSelectedMinutes.text = "$minutes minutes"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Select QR Code Validity")
            .setView(dialogView)
            .setPositiveButton("Generate") { _, _ ->
                val minutes = seekBar.progress + 1 // Add 1 because progress starts at 0
                onTimeSelected(minutes)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQRCodeDialog(qrImage: String, expiresAt: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_code, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.ivQRCode)
        val tvExpiry = dialogView.findViewById<TextView>(R.id.tvExpiry)
        
        try {
            // Convert base64 to bitmap
            val base64Data = qrImage.substring(qrImage.indexOf(",") + 1)
            val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            imageView.setImageBitmap(bitmap)
            
            // Format expiry time
            val expiryTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                .parse(expiresAt)
            val formattedTime = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(expiryTime!!)
            tvExpiry.text = "Expires at: $formattedTime"
            
            AlertDialog.Builder(this)
                .setTitle("QR Code")
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error displaying QR code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Function to display the stored QR code for a given course
    private fun showStoredQRCode(course: Course) {
        val qrData = (activeQRData as? MutableMap<Int, QRCodeData>)?.get(course.course_id)
        if (qrData != null) {
            showQRCodeDialog(qrData.qrImage, qrData.expiresAt)
        } else {
            Toast.makeText(this, "No active QR code found for this course", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCourseDetails(course: Course) {
        AlertDialog.Builder(this)
            .setTitle(course.course_code)
            .setMessage("""
                Course Name: ${course.course_name}
                Lecturer: ${course.lecturer_name}
                Students Enrolled: ${course.student_count}
            """.trimIndent())
            .setPositiveButton("Close", null)
            .show()
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
                val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                val token = prefs.getString("token", null)
                
                if (token == null) {
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Please log in again", 
                        Toast.LENGTH_SHORT).show()
                    // Redirect to login
                    startActivity(Intent(this@LecturerDashboardActivity, LoginActivity::class.java))
                    finish()
                    return@launch
                }

                val response = apiService.deleteCourse("Bearer $token", course.course_id)
                if (response.isSuccessful) {
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Course deleted successfully", 
                        Toast.LENGTH_SHORT).show()
                    loadCourses() // Reload the courses list
                } else {
                    val errorBody = response.errorBody()?.string()
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Failed to delete course: $errorBody", 
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace() // Log the full stack trace
                Toast.makeText(this@LecturerDashboardActivity, 
                    "Error deleting course: ${e.message}", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadCourses() {
        lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                val token = prefs.getString("token", null)
                
                if (token == null) {
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Please log in again", 
                        Toast.LENGTH_SHORT).show()
                    // Redirect to login
                    startActivity(Intent(this@LecturerDashboardActivity, LoginActivity::class.java))
                    finish()
                    return@launch
                }

                val response = apiService.getCourses("Bearer $token")
                if (response.isSuccessful) {
                    val courses = response.body()?.courses ?: emptyList()
                    courseAdapter.submitList(courses)
                    updateNoCoursesVisibility(courses)
                    
                    // Update active QR status for courses
                    courses.forEach { course ->
                        if ((activeQRData as? MutableMap<Int, QRCodeData>)?.containsKey(course.course_id) == true) {
                            val qrData = (activeQRData as MutableMap<Int, QRCodeData>)[course.course_id]
                            qrData?.let {
                                courseAdapter.setActiveQRCode(course.course_id, it.remainingSeconds)
                                courseAdapter.setShowQRButton(course.course_id, true)
                            }
                        }
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Failed to load courses: $errorBody", 
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace() // Log the full stack trace
                Toast.makeText(this@LecturerDashboardActivity, 
                    "Error loading courses: ${e.message}", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateNoCoursesVisibility(courses: List<Course>) {
        binding.apply {
            if (courses.isEmpty()) {
                tvNoCourses.visibility = ViewGroup.VISIBLE
                rvCourses.visibility = ViewGroup.GONE
            } else {
                tvNoCourses.visibility = ViewGroup.GONE
                rvCourses.visibility = ViewGroup.VISIBLE
            }
        }
    }

    private fun generateQRCode(course: Course) {
        showQRTimeSelectionDialog { minutes ->
            lifecycleScope.launch {
                try {
                    val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                    val token = prefs.getString("token", null)
                    
                    if (token == null) {
                        Toast.makeText(this@LecturerDashboardActivity, 
                            "Please log in again", 
                            Toast.LENGTH_SHORT).show()
                        // Redirect to login
                        startActivity(Intent(this@LecturerDashboardActivity, LoginActivity::class.java))
                        finish()
                        return@launch
                    }
                        
                    val response = apiService.generateQRCode(
                        token = "Bearer $token",
                        courseId = course.course_id,
                        request = QRCodeRequest(expiry_minutes = minutes)
                    )
                    
                    if (response.isSuccessful) {
                        response.body()?.let { qrResponse ->
                            // Store QR code data
                            (activeQRData as? MutableMap<Int, QRCodeData>)?.let { map ->
                                map[course.course_id] = QRCodeData(
                                    qrImage = qrResponse.qr_image,
                                    expiresAt = qrResponse.expires_at,
                                    remainingSeconds = qrResponse.remaining_seconds,
                                    courseId = course.course_id
                                )
                            } ?: run {
                                // Initialize map if not done
                                activeQRData = mutableMapOf(
                                    course.course_id to QRCodeData(
                                        qrImage = qrResponse.qr_image,
                                        expiresAt = qrResponse.expires_at,
                                        remainingSeconds = qrResponse.remaining_seconds,
                                        courseId = course.course_id
                                    )
                                )
                            }
                            
                            // Show QR code
                            showQRCodeDialog(qrResponse.qr_image, qrResponse.expires_at)
                            
                            // Update UI to show active QR code and show QR button
                            courseAdapter.setActiveQRCode(course.course_id, qrResponse.remaining_seconds)
                            courseAdapter.setShowQRButton(course.course_id, true)
                            
                            // Start countdown timer
                            startCountdownTimer(course.course_id, qrResponse.remaining_seconds)
                        } ?: run {
                            Toast.makeText(this@LecturerDashboardActivity, 
                                "Invalid QR code response", 
                                Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val errorBody = response.errorBody()?.string()
                        Toast.makeText(this@LecturerDashboardActivity, 
                            "Failed to generate QR code: $errorBody", 
                            Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // Log the full stack trace
                    Toast.makeText(this@LecturerDashboardActivity, 
                        "Error generating QR code: ${e.message}", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startCountdownTimer(courseId: Int, remainingSeconds: Int) {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(remainingSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                
                // Update the remaining seconds in stored data
                (activeQRData as? MutableMap<Int, QRCodeData>)?.get(courseId)?.let { qrData ->
                    (activeQRData as MutableMap<Int, QRCodeData>)[courseId] = qrData.copy(remainingSeconds = seconds)
                }
                
                // Update adapter
                courseAdapter.setActiveQRCode(courseId, seconds)
            }

            override fun onFinish() {
                // Remove the expired QR code from storage
                (activeQRData as? MutableMap<Int, QRCodeData>)?.remove(courseId)
                
                // Clear active QR code from adapter
                courseAdapter.clearActiveQRCode(courseId)
                courseAdapter.setShowQRButton(courseId, false)
            }
        }.start()
    }

    private fun openDeleteConfirmationDialog(courseId: Int) {
        // Find the course object from the courseId
        val course = courseAdapter.currentList.find { it.course_id == courseId }
        if (course != null) {
            showDeleteConfirmationDialog(course)
        } else {
            Toast.makeText(this, "Course not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCourseQRDialog(courseId: Int) {
        // Find the course object from the courseId
        val course = courseAdapter.currentList.find { it.course_id == courseId }
        if (course != null) {
            generateQRCode(course)
        } else {
            Toast.makeText(this, "Course not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAttendanceReport(course: Course) {
        val intent = Intent(this, AttendanceReportActivity::class.java).apply {
            putExtra("COURSE_ID", course.course_id)
            putExtra("COURSE_CODE", course.course_code)
            putExtra("COURSE_NAME", course.course_name)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        loadCourses() // Reload courses when returning from AddCourseActivity
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel any active countdowns
        countdownTimer?.cancel()
        
        // Clear the adapter state to avoid issues if another user logs in
        if (::courseAdapter.isInitialized) {
            courseAdapter.clearAttendedCourses()
        }
        
        Log.d("LecturerDashboard", "Activity destroyed, cleared adapter state")
    }
} 