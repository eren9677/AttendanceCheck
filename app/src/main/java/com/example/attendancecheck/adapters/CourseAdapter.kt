package com.example.attendancecheck.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.attendancecheck.R
import com.example.attendancecheck.api.Course
import com.google.android.material.button.MaterialButton

class CourseAdapter(
    private val onEnrollClick: ((Course) -> Unit)? = null
) : ListAdapter<Course, CourseAdapter.CourseViewHolder>(CourseDiffCallback()) {

    private var isShowingAvailableCourses = true

    fun setShowingAvailableCourses(isAvailable: Boolean) {
        isShowingAvailableCourses = isAvailable
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course, parent, false)
        return CourseViewHolder(view)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCourseCode: TextView = itemView.findViewById(R.id.tvCourseCode)
        private val tvCourseName: TextView = itemView.findViewById(R.id.tvCourseName)
        private val tvLecturerName: TextView = itemView.findViewById(R.id.tvLecturerName)
        private val tvAttendance: TextView = itemView.findViewById(R.id.tvAttendance)
        private val btnEnroll: MaterialButton = itemView.findViewById(R.id.btnEnroll)

        fun bind(course: Course) {
            tvCourseCode.text = course.course_code
            tvCourseName.text = course.course_name
            tvLecturerName.text = "Lecturer: ${course.lecturer_name}"
            
            if (isShowingAvailableCourses) {
                btnEnroll.visibility = View.VISIBLE
                btnEnroll.setOnClickListener { onEnrollClick?.invoke(course) }
                tvAttendance.text = "Click to enroll"
            } else {
                btnEnroll.visibility = View.GONE
                if (course.student_count != null) {
                    tvAttendance.text = "Enrolled Students: ${course.student_count}"
                } else {
                    tvAttendance.text = "Attendance: 0%" // Placeholder for actual attendance data
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