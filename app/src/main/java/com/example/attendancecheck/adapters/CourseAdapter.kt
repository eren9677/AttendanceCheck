package com.example.attendancecheck.adapters

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
    private val onEnrollClick: (Course) -> Unit
) : ListAdapter<Course, CourseAdapter.CourseViewHolder>(CourseDiffCallback()) {

    private var isShowingAvailableCourses = true
    private var isLecturerView = false
    private var activeQRCodeCourseId: Int? = null
    private var remainingSeconds: Int = 0

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

    fun clearActiveQRCode() {
        activeQRCodeCourseId = null
        remainingSeconds = 0
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

                // Set button click listeners
                btnEnroll.setOnClickListener { onEnrollClick(course) }
                btnDelete.setOnClickListener { onDeleteClick(course) }
                btnGenerateQR.setOnClickListener { onGenerateQRClick(course) }

                // Handle active QR code state
                if (course.course_id == activeQRCodeCourseId) {
                    root.setBackgroundResource(R.drawable.bg_course_active)
                    tvRemainingTime.visibility = ViewGroup.VISIBLE
                    tvRemainingTime.text = "QR Code expires in: ${remainingSeconds}s"
                } else {
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