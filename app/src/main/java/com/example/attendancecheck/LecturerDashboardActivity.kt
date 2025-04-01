package com.example.attendancecheck

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.TimePicker
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
            onCourseClick = { course -> showCourseDetails(course) },
            onDeleteClick = { course -> showDeleteConfirmationDialog(course) },
            onGenerateQRClick = { course -> generateQRCode(course) },
            onEnrollClick = { _ -> /* Not used in lecturer view */ }
        )
        courseAdapter.setLecturerView(true)
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
                            showQRCodeDialog(qrResponse.qr_image, qrResponse.expires_at)
                            courseAdapter.setActiveQRCode(course.course_id, qrResponse.remaining_seconds)
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
                courseAdapter.setActiveQRCode(courseId, seconds)
            }

            override fun onFinish() {
                courseAdapter.clearActiveQRCode()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        loadCourses() // Reload courses when returning from AddCourseActivity
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }
} 