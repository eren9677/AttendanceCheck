package com.example.attendancecheck

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.attendancecheck.api.ApiService
import com.example.attendancecheck.api.StudentAttendance
import com.example.attendancecheck.databinding.ActivityAttendanceReportBinding
import com.example.attendancecheck.databinding.ItemAttendanceHeaderBinding
import com.example.attendancecheck.databinding.ItemAttendanceRowBinding
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class AttendanceReportActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAttendanceReportBinding
    private lateinit var apiService: ApiService
    private var courseId: Int = -1
    private var courseCode: String = ""
    private var courseName: String = ""
    private lateinit var adapter: AttendanceAdapter
    private var dates: List<String> = emptyList()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get course info from intent
        courseId = intent.getIntExtra("COURSE_ID", -1)
        courseCode = intent.getStringExtra("COURSE_CODE") ?: ""
        courseName = intent.getStringExtra("COURSE_NAME") ?: ""
        
        if (courseId == -1) {
            Toast.makeText(this, "Invalid course information", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Attendance Report"
        
        // Set course info
        binding.tvCourseInfo.text = "$courseCode: $courseName"
        
        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        apiService = retrofit.create(ApiService::class.java)
        
        // Set up RecyclerView
        adapter = AttendanceAdapter()
        binding.rvAttendance.apply {
            layoutManager = LinearLayoutManager(this@AttendanceReportActivity)
            adapter = this@AttendanceReportActivity.adapter
        }
        
        // Load attendance data
        loadAttendanceData()
    }
    
    private fun loadAttendanceData() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvNoData.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                val token = prefs.getString("token", null)
                
                if (token == null) {
                    Toast.makeText(this@AttendanceReportActivity, 
                        "Please log in again", 
                        Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                
                // Log the request for debugging
                Log.d("AttendanceReport", "Requesting attendance data for course ID: $courseId")
                
                val response = apiService.getCourseAttendance("Bearer $token", courseId)
                
                binding.progressBar.visibility = View.GONE
                
                if (response.isSuccessful) {
                    val attendanceData = response.body()
                    
                    // Log the response for debugging
                    Log.d("AttendanceReport", "Response successful. Students: ${attendanceData?.students?.size}, Dates: ${attendanceData?.dates?.size}")
                    
                    if (attendanceData != null && attendanceData.students.isNotEmpty() && attendanceData.dates.isNotEmpty()) {
                        // Store dates for header
                        dates = attendanceData.dates.sortedByDescending { it }
                        
                        // Update UI with attendance data
                        adapter.submitList(attendanceData.students)
                    } else {
                        Log.d("AttendanceReport", "No attendance data found: students=${attendanceData?.students?.size ?: 0}, dates=${attendanceData?.dates?.size ?: 0}")
                        binding.tvNoData.visibility = View.VISIBLE
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    Log.e("AttendanceReport", "Failed to load attendance data: ${response.code()} - $errorBody")
                    
                    Toast.makeText(this@AttendanceReportActivity, 
                        "Failed to load attendance data: ${response.message()}", 
                        Toast.LENGTH_SHORT).show()
                    binding.tvNoData.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.tvNoData.visibility = View.VISIBLE
                
                Log.e("AttendanceReport", "Exception loading attendance data", e)
                
                Toast.makeText(this@AttendanceReportActivity, 
                    "Error loading attendance data: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    inner class AttendanceAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val HEADER_TYPE = 0
        private val ITEM_TYPE = 1
        private var students: List<StudentAttendance> = emptyList()
        
        fun submitList(newStudents: List<StudentAttendance>) {
            students = newStudents
            notifyDataSetChanged()
        }
        
        override fun getItemViewType(position: Int): Int {
            return if (position == 0) HEADER_TYPE else ITEM_TYPE
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == HEADER_TYPE) {
                val view = LayoutInflater.from(parent.context).inflate(
                    R.layout.item_attendance_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(
                    R.layout.item_attendance_row, parent, false)
                ItemViewHolder(view)
            }
        }
        
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderViewHolder) {
                holder.bind(dates)
            } else if (holder is ItemViewHolder) {
                holder.bind(students[position - 1])
            }
        }
        
        override fun getItemCount(): Int = if (students.isEmpty()) 0 else students.size + 1
        
        inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val dateHeadersContainer: LinearLayout = itemView.findViewById(R.id.llDateHeaders)
            
            fun bind(dates: List<String>) {
                // Clear previous headers
                dateHeadersContainer.removeAllViews()
                
                // Add a date header for each date
                for (date in dates) {
                    val dateView = LayoutInflater.from(itemView.context).inflate(
                        R.layout.item_attendance_cell, dateHeadersContainer, false)
                    
                    // Format the date (YYYY-MM-DD to DD/MM)
                    val formattedDate = try {
                        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val outputFormat = SimpleDateFormat("dd/MM", Locale.getDefault())
                        val dateObj = inputFormat.parse(date)
                        outputFormat.format(dateObj!!)
                    } catch (e: Exception) {
                        date
                    }
                    
                    // Set date text
                    (dateView as TextView).apply {
                        text = formattedDate
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                    }
                    
                    // Add to container
                    dateHeadersContainer.addView(dateView)
                }
            }
        }
        
        inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvStudentId: TextView = itemView.findViewById(R.id.tvStudentId)
            private val tvStudentName: TextView = itemView.findViewById(R.id.tvStudentName)
            private val attendanceCellsContainer: LinearLayout = itemView.findViewById(R.id.llAttendanceDates)
            
            fun bind(student: StudentAttendance) {
                // Set student info
                tvStudentId.text = student.student_id
                tvStudentName.text = student.student_name
                
                // Log student attendance data for debugging
                Log.d("AttendanceReport", "Binding student: ${student.student_name}, Attendance entries: ${student.attendance.size}")
                
                // Clear previous attendance cells
                attendanceCellsContainer.removeAllViews()
                
                // Add an attendance cell for each date
                for (date in dates) {
                    val cellView = LayoutInflater.from(itemView.context).inflate(
                        R.layout.item_attendance_cell, attendanceCellsContainer, false)
                    
                    // Check if we have an attendance record for this date
                    val isPresent = student.attendance[date] ?: false
                    
                    // Log the attendance status for this cell for debugging
                    Log.d("AttendanceReport", "Student: ${student.student_name}, Date: $date, Present: $isPresent")
                    
                    (cellView as TextView).apply {
                        text = if (isPresent) "+" else "-"
                        
                        if (isPresent) {
                            setTextColor(Color.parseColor("#4CAF50")) // Green
                        } else {
                            setTextColor(Color.parseColor("#F44336")) // Red
                        }
                    }
                    
                    // Add to container
                    attendanceCellsContainer.addView(cellView)
                }
            }
        }
    }
} 