package com.example.attendancecheck.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.attendancecheck.R
import com.example.attendancecheck.api.Course

class CourseAdapter : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {
    private var courses: List<Course> = emptyList()
    private var isLecturerView: Boolean = false

    fun submitList(newCourses: List<Course>) {
        courses = newCourses
        notifyDataSetChanged()
    }

    fun setLecturerView(isLecturer: Boolean) {
        isLecturerView = isLecturer
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course, parent, false)
        return CourseViewHolder(view)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(courses[position])
    }

    override fun getItemCount() = courses.size

    inner class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val courseCode: TextView = itemView.findViewById(R.id.tvCourseCode)
        private val courseName: TextView = itemView.findViewById(R.id.tvCourseName)
        private val attendance: TextView = itemView.findViewById(R.id.tvAttendance)

        fun bind(course: Course) {
            courseCode.text = course.course_code
            courseName.text = course.course_name
            if (isLecturerView) {
                attendance.text = "Enrolled Students: 0" // TODO: Get actual count
            } else {
                attendance.text = "Attendance: 0%" // TODO: Calculate actual percentage
            }
        }
    }
} 