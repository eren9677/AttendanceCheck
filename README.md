# AttendanceCheck

AttendanceCheck is a mobile application for simplifying classroom attendance tracking using QR codes. The system features separate interfaces for lecturers and students. Lecturers can create courses, generate time-limited QR codes for attendance sessions, and view comprehensive attendance reports that show which students were present on specific dates. Students can enroll in available courses, scan QR codes to mark their attendance during lectures, and view their personal attendance history. The application provides real-time QR code expiration countdowns, attendance completion indicators, and organized tabular reports, making the entire attendance process paperless, efficient, and fraud-resistant.

## Features

- **User Authentication**: Secure login and registration for students and lecturers
- **Course Management**: Create, view, and delete courses with proper cascade operations
- **QR Code Generation**: Create time-limited attendance QR codes for specific lectures
- **Mobile Scanning**: Students can scan QR codes to record attendance in real-time
- **Attendance Reports**: View comprehensive reports of student attendance across multiple dates
- **Student Enrollment**: Students can browse and enroll in available courses
- **Real-time Notifications**: Countdown timers for QR code expiration
- **Data Security**: User-specific data isolation and proper session management

## Technology Stack

- **Frontend**: Native Android application written in Kotlin
- **Backend**: Python Flask REST API
- **Database**: MySQL relational database
- **Authentication**: JWT-based token authentication 