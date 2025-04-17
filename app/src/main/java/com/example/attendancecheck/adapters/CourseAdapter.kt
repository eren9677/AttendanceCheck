package com.example.attendancecheck.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.attendancecheck.R
import com.example.attendancecheck.api.Course
import com.example.attendancecheck.databinding.ItemCourseBinding

class CourseAdapter(
    private val onCourseClick: (Course) -> Unit,
    private val onDeleteClick: (Course) -> Unit,
    private val onGenerateQRClick: (Course) -> Unit,
    private val onShowQRClick: (Course) -> Unit,
    private val onEnrollClick: (Course) -> Unit,
    private val onCheckAttendanceClick: (Course) -> Unit
) : ListAdapter<Course, CourseAdapter.CourseViewHolder>(CourseDiffCallback()) {

    private var isShowingAvailableCourses = true
    private var isLecturerView = false
    private var activeQRCodeCourseId: Int? = null
    private var coursesWithShowQRButton = mutableSetOf<Int>()
    private var remainingSeconds: Int = 0
    
    // Track attended courses
    private val attendedCourses = mutableSetOf<Int>()

    fun setShowingAvailableCourses(showing: Boolean) {
        isShowingAvailableCourses = showing
        notifyDataSetChanged()
    }

    fun setLecturerView(isLecturer: Boolean) {
        isLecturerView = isLecturer
        notifyDataSetChanged()
    }

    fun setActiveQRCode(courseId: Int, remaining: Int) {
        activeQRCodeCourseId = courseId
        remainingSeconds = remaining
        notifyDataSetChanged()
    }

    fun clearActiveQRCode(courseId: Int? = null) {
        if (courseId == null || courseId == activeQRCodeCourseId) {
            activeQRCodeCourseId = null
            remainingSeconds = 0
            notifyDataSetChanged()
        }
    }
    
    fun setShowQRButton(courseId: Int, show: Boolean) {
        if (show) {
            coursesWithShowQRButton.add(courseId)
        } else {
            coursesWithShowQRButton.remove(courseId)
        }
        notifyDataSetChanged()
    }
    
    /**
     * Mark a course as attended for the current session
     * @param courseId ID of the course that was attended
     */
    fun markCourseAsAttended(courseId: Int) {
        attendedCourses.add(courseId)
        notifyDataSetChanged()
    }
    
    /**
     * Check if a course has been attended
     * @param courseId ID of the course to check
     * @return true if course has been attended
     */
    fun isCourseAttended(courseId: Int): Boolean {
        return attendedCourses.contains(courseId)
    }
    
    /**
     * Clear all attended courses (usually on logout)
     */
    fun clearAttendedCourses() {
        attendedCourses.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val binding = ItemCourseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CourseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CourseViewHolder(
        private val binding: ItemCourseBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(course: Course) {
            binding.apply {
                root.setOnClickListener { onCourseClick(course) }
                
                tvCourseCode.text = course.course_code
                tvCourseName.text = course.course_name
                tvLecturerName.text = "Lecturer: ${course.lecturer_name}"
                
                if (isLecturerView) {
                    tvStudentCount.text = "Students: ${course.student_count}"
                    tvStudentCount.visibility = ViewGroup.VISIBLE
                } else {
                    tvStudentCount.visibility = ViewGroup.GONE
                }

                // Handle button visibility based on view type
                btnEnroll.visibility = if (isShowingAvailableCourses && !isLecturerView) {
                    ViewGroup.VISIBLE
                } else {
                    ViewGroup.GONE
                }
                btnDelete.visibility = if (isLecturerView) ViewGroup.VISIBLE else ViewGroup.GONE
                btnGenerateQR.visibility = if (isLecturerView) ViewGroup.VISIBLE else ViewGroup.GONE
                btnCheckAttendance.visibility = if (isLecturerView) ViewGroup.VISIBLE else ViewGroup.GONE
                
                // Show QR button visibility
                btnShowQR.visibility = if (isLecturerView && coursesWithShowQRButton.contains(course.course_id)) {
                    ViewGroup.VISIBLE
                } else {
                    ViewGroup.GONE
                }

                // Set button click listeners
                btnEnroll.setOnClickListener { onEnrollClick(course) }
                btnDelete.setOnClickListener { onDeleteClick(course) }
                btnGenerateQR.setOnClickListener { onGenerateQRClick(course) }
                btnShowQR.setOnClickListener { onShowQRClick(course) }
                btnCheckAttendance.setOnClickListener { onCheckAttendanceClick(course) }

                // Check if the course has been attended
                val isAttended = attendedCourses.contains(course.course_id)
                
                // Set background and attendance indicators
                if (isAttended && course.has_active_qr) {
                    // Course is attended and has active QR code - show green success background and message
                    root.setBackgroundResource(R.drawable.bg_course_attended)
                    tvRemainingTime.visibility = ViewGroup.VISIBLE
                    tvRemainingTime.text = "✓ Attendance Completed"
                    tvRemainingTime.setTextColor(Color.parseColor("#4CAF50")) // Green color
                } else if (course.course_id == activeQRCodeCourseId && !isAttended) {
                    // Course has active QR code but not attended - show regular active background with timer
                    root.setBackgroundResource(R.drawable.bg_course_active)
                    tvRemainingTime.visibility = ViewGroup.VISIBLE
                    tvRemainingTime.text = "QR Code expires in: ${remainingSeconds}s"
                    tvRemainingTime.setTextColor(Color.RED) // Default red color for countdown
                } else {
                    // Regular course - normal background
                    root.setBackgroundResource(R.drawable.bg_course)
                    tvRemainingTime.visibility = ViewGroup.GONE
                }
            }
        }
    }

    private class CourseDiffCallback : DiffUtil.ItemCallback<Course>() {
        override fun areItemsTheSame(oldItem: Course, newItem: Course): Boolean {
            return oldItem.course_id == newItem.course_id
        }

        override fun areContentsTheSame(oldItem: Course, newItem: Course): Boolean {
            return oldItem == newItem
        }
    }
} 