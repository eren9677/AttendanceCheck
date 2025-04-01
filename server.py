from flask import Flask, jsonify, request, session
import mysql.connector
from mysql.connector import Error
import jwt
import datetime
import uuid
import bcrypt
import qrcode
from io import BytesIO
import base64

# Database configuration
db_config = {
    "host": "localhost",
    "user": "root",
    "password": "",
    "database": "qr_attendance_system"
}

app = Flask(__name__)
app.secret_key = "your-secret-key-for-sessions"  # Change this to a secure key
JWT_SECRET = "your-jwt-secret-key"  # Change this to a secure key

# Helper function to get database connection
def get_db_connection():
    try:
        conn = mysql.connector.connect(**db_config)
        return conn
    except Error as e:
        print(f"Error connecting to MySQL: {e}")
        return None

# Authentication APIs
@app.route('/api/login', methods=['POST'])
def login():
    data = request.get_json()
    university_id = data.get('university_id')
    password = data.get('password')
    
    if not university_id or not password:
        return jsonify({"error": "University ID and password are required"}), 400
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        cursor.execute("SELECT * FROM users WHERE university_id = %s", (university_id,))
        user = cursor.fetchone()
        
        if not user or not bcrypt.checkpw(password.encode('utf-8'), user['password'].encode('utf-8')):
            return jsonify({"error": "Invalid credentials"}), 401
        
        # Generate JWT token
        token = jwt.encode({
            'user_id': user['user_id'],
            'university_id': user['university_id'],
            'role': user['role'],
            'exp': datetime.datetime.utcnow() + datetime.timedelta(hours=24)
        }, JWT_SECRET, algorithm="HS256")
        
        return jsonify({
            "message": "Login successful",
            "token": token,
            "user": {
                "user_id": user['user_id'],
                "name": user['name'],
                "university_id": user['university_id'],
                "role": user['role']
            }
        }), 200
    except Error as e:
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

@app.route('/api/register', methods=['POST'])
def register():
    data = request.get_json()
    university_id = data.get('university_id')
    password = data.get('password')
    name = data.get('name')
    role = data.get('role')
    
    if not all([university_id, password, name, role]):
        return jsonify({"error": "All fields are required"}), 400
    
    if role not in ['student', 'lecturer']:
        return jsonify({"error": "Role must be either 'student' or 'lecturer'"}), 400
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Check if user already exists
        cursor.execute("SELECT * FROM users WHERE university_id = %s", (university_id,))
        if cursor.fetchone():
            return jsonify({"error": "User with this university ID already exists"}), 409
        
        # Hash the password
        hashed_password = bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt()).decode('utf-8')
        
        # Insert new user
        cursor.execute(
            "INSERT INTO users (university_id, password, name, role) VALUES (%s, %s, %s, %s)",
            (university_id, hashed_password, name, role)
        )
        conn.commit()
        
        return jsonify({"message": "User registered successfully"}), 201
    except Error as e:
        conn.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

# Middleware for JWT authentication
def token_required(f):
    def decorated(*args, **kwargs):
        token = None
        auth_header = request.headers.get('Authorization')
        
        if auth_header and auth_header.startswith('Bearer '):
            token = auth_header.split(" ")[1]
        
        if not token:
            return jsonify({"error": "Token is missing"}), 401
        
        try:
            data = jwt.decode(token, JWT_SECRET, algorithms=["HS256"])
            
            conn = get_db_connection()
            if not conn:
                return jsonify({"error": "Database connection failed"}), 500
            
            cursor = conn.cursor(dictionary=True)
            cursor.execute("SELECT * FROM users WHERE user_id = %s", (data['user_id'],))
            current_user = cursor.fetchone()
            cursor.close()
            conn.close()
            
            if not current_user:
                return jsonify({"error": "User not found"}), 401
        except jwt.ExpiredSignatureError:
            return jsonify({"error": "Token has expired"}), 401
        except jwt.InvalidTokenError:
            return jsonify({"error": "Invalid token"}), 401
        
        return f(current_user, *args, **kwargs)
    
    decorated.__name__ = f.__name__
    return decorated

# Course APIs
@app.route('/api/courses', methods=['GET'])
@token_required
def get_courses(current_user):
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        if current_user['role'] == 'lecturer':
            # For lecturers, get their courses with student counts
            cursor.execute(
                """
                SELECT c.*, u.name as lecturer_name,
                       (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = c.course_id) as student_count
                FROM courses c
                JOIN users u ON c.lecturer_id = u.user_id
                WHERE c.lecturer_id = %s
                """,
                (current_user['user_id'],)
            )
        else:  # student
            # For students, get their enrolled courses with lecturer names
            cursor.execute(
                """
                SELECT c.*, u.name as lecturer_name
                FROM courses c
                JOIN enrollments e ON c.course_id = e.course_id
                JOIN users u ON c.lecturer_id = u.user_id
                WHERE e.student_id = %s
                """,
                (current_user['user_id'],)
            )
        
        courses = cursor.fetchall()
        return jsonify({"courses": courses}), 200
    except Error as e:
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

@app.route('/api/courses/all', methods=['GET'])
@token_required
def get_all_courses(current_user):
    if current_user['role'] != 'student':
        return jsonify({"error": "Only students can view all courses"}), 403
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Get all courses except those the student is already enrolled in
        cursor.execute(
            """
            SELECT c.*, u.name as lecturer_name 
            FROM courses c 
            JOIN users u ON c.lecturer_id = u.user_id 
            WHERE c.course_id NOT IN (
                SELECT course_id 
                FROM enrollments 
                WHERE student_id = %s
            )
            """,
            (current_user['user_id'],)
        )
        
        courses = cursor.fetchall()
        return jsonify({"courses": courses}), 200
    except Error as e:
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

@app.route('/api/courses', methods=['POST'])
@token_required
def create_course(current_user):
    if current_user['role'] != 'lecturer':
        return jsonify({"error": "Only lecturers can create courses"}), 403
    
    data = request.get_json()
    course_code = data.get('course_code')
    course_name = data.get('course_name')
    
    if not course_code or not course_name:
        return jsonify({"error": "Course code and name are required"}), 400
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        cursor.execute(
            "INSERT INTO courses (course_code, course_name, lecturer_id) VALUES (%s, %s, %s)",
            (course_code, course_name, current_user['user_id'])
        )
        conn.commit()
        
        return jsonify({
            "message": "Course created successfully",
            "course_id": cursor.lastrowid
        }), 201
    except Error as e:
        conn.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

# Lecture APIs
@app.route('/api/lectures', methods=['POST'])
@token_required
def create_lecture(current_user):
    if current_user['role'] != 'lecturer':
        return jsonify({"error": "Only lecturers can create lectures"}), 403
    
    data = request.get_json()
    course_id = data.get('course_id')
    date = data.get('date')
    start_time = data.get('start_time')
    end_time = data.get('end_time')
    
    if not all([course_id, date, start_time, end_time]):
        return jsonify({"error": "All fields are required"}), 400
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Verify the lecturer owns this course
        cursor.execute(
            "SELECT * FROM courses WHERE course_id = %s AND lecturer_id = %s",
            (course_id, current_user['user_id'])
        )
        if not cursor.fetchone():
            return jsonify({"error": "Course not found or you don't have permission"}), 404
        
        # Create the lecture
        cursor.execute(
            "INSERT INTO lectures (course_id, date, start_time, end_time) VALUES (%s, %s, %s, %s)",
            (course_id, date, start_time, end_time)
        )
        conn.commit()
        
        return jsonify({
            "message": "Lecture created successfully",
            "lecture_id": cursor.lastrowid
        }), 201
    except Error as e:
        conn.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

@app.route('/api/courses/<int:course_id>/lectures', methods=['GET'])
@token_required
def get_lectures(current_user, course_id):
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        if current_user['role'] == 'lecturer':
            # Verify the lecturer owns this course
            cursor.execute(
                "SELECT * FROM courses WHERE course_id = %s AND lecturer_id = %s",
                (course_id, current_user['user_id'])
            )
            if not cursor.fetchone():
                return jsonify({"error": "Course not found or you don't have permission"}), 404
        else:  # student
            # Verify the student is enrolled in this course
            cursor.execute(
                "SELECT * FROM enrollments WHERE course_id = %s AND student_id = %s",
                (course_id, current_user['user_id'])
            )
            if not cursor.fetchone():
                return jsonify({"error": "You are not enrolled in this course"}), 403
        
        # Get lectures for the course
        cursor.execute(
            "SELECT * FROM lectures WHERE course_id = %s ORDER BY date DESC, start_time DESC",
            (course_id,)
        )
        lectures = cursor.fetchall()
        
        return jsonify({"lectures": lectures}), 200
    except Error as e:
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

# QR Code APIs
@app.route('/api/lectures/<int:lecture_id>/qrcode', methods=['POST'])
@token_required
def generate_qr_code(current_user, lecture_id):
    if current_user['role'] != 'lecturer':
        return jsonify({"error": "Only lecturers can generate QR codes"}), 403
    
    data = request.get_json()
    expiry_minutes = data.get('expiry_minutes', 15)  # Default to 15 minutes
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Verify the lecturer owns this lecture
        cursor.execute(
            "SELECT l.* FROM lectures l JOIN courses c ON l.course_id = c.course_id WHERE l.lecture_id = %s AND c.lecturer_id = %s",
            (lecture_id, current_user['user_id'])
        )
        lecture = cursor.fetchone()
        if not lecture:
            return jsonify({"error": "Lecture not found or you don't have permission"}), 404
        
        # Generate a unique token
        token = str(uuid.uuid4())
        
        # Calculate expiry time
        expires_at = datetime.datetime.now() + datetime.timedelta(minutes=expiry_minutes)
        
        # Save QR code info to database
        cursor.execute(
            "INSERT INTO qr_codes (lecture_id, token, expires_at) VALUES (%s, %s, %s)",
            (lecture_id, token, expires_at)
        )
        conn.commit()
        qr_id = cursor.lastrowid
        
        # Generate QR code
        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_L,
            box_size=10,
            border=4,
        )
        qr.add_data(token)
        qr.make(fit=True)
        
        img = qr.make_image(fill_color="black", back_color="white")
        buffered = BytesIO()
        img.save(buffered)
        img_str = base64.b64encode(buffered.getvalue()).decode()
        
        return jsonify({
            "qr_id": qr_id,
            "token": token,
            "expires_at": expires_at.isoformat(),
            "qr_image": f"data:image/png;base64,{img_str}"
        }), 200
    except Error as e:
        conn.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

# Attendance APIs
@app.route('/api/attendance/check-in', methods=['POST'])
@token_required
def check_in(current_user):
    if current_user['role'] != 'student':
        return jsonify({"error": "Only students can check in to lectures"}), 403
    
    data = request.get_json()
    token = data.get('token')
    
    if not token:
        return jsonify({"error": "QR code token is required"}), 400
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Verify QR code is valid and not expired
        cursor.execute(
            "SELECT qr.*, l.course_id FROM qr_codes qr JOIN lectures l ON qr.lecture_id = l.lecture_id WHERE qr.token = %s AND qr.expires_at > NOW()",
            (token,)
        )
        qr_data = cursor.fetchone()
        
        if not qr_data:
            return jsonify({"error": "Invalid or expired QR code"}), 400
        
        # Verify student is enrolled in the course
        cursor.execute(
            "SELECT * FROM enrollments WHERE student_id = %s AND course_id = %s",
            (current_user['user_id'], qr_data['course_id'])
        )
        if not cursor.fetchone():
            return jsonify({"error": "You are not enrolled in this course"}), 403
        
        # Check if already checked in
        cursor.execute(
            "SELECT * FROM attendance WHERE student_id = %s AND lecture_id = %s",
            (current_user['user_id'], qr_data['lecture_id'])
        )
        if cursor.fetchone():
            return jsonify({"error": "You have already checked in to this lecture"}), 400
        
        # Record attendance
        cursor.execute(
            "INSERT INTO attendance (student_id, lecture_id, qr_id) VALUES (%s, %s, %s)",
            (current_user['user_id'], qr_data['lecture_id'], qr_data['qr_id'])
        )
        conn.commit()
        
        return jsonify({"message": "Attendance recorded successfully"}), 201
    except Error as e:
        conn.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

@app.route('/api/lectures/<int:lecture_id>/attendance', methods=['GET'])
@token_required
def get_lecture_attendance(current_user, lecture_id):
    if current_user['role'] != 'lecturer':
        return jsonify({"error": "Only lecturers can view attendance records"}), 403
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Verify the lecturer owns this lecture
        cursor.execute(
            "SELECT l.* FROM lectures l JOIN courses c ON l.course_id = c.course_id WHERE l.lecture_id = %s AND c.lecturer_id = %s",
            (lecture_id, current_user['user_id'])
        )
        lecture = cursor.fetchone()
        if not lecture:
            return jsonify({"error": "Lecture not found or you don't have permission"}), 404
        
        # Get all students enrolled in the course
        cursor.execute(
            """
            SELECT u.user_id, u.name, u.university_id,
                   CASE WHEN a.attendance_id IS NOT NULL THEN 'present' ELSE 'absent' END as status,
                   a.timestamp as check_in_time
            FROM users u
            JOIN enrollments e ON u.user_id = e.student_id
            LEFT JOIN attendance a ON u.user_id = a.student_id AND a.lecture_id = %s
            WHERE e.course_id = %s AND u.role = 'student'
            """,
            (lecture_id, lecture['course_id'])
        )
        attendance_records = cursor.fetchall()
        
        return jsonify({
            "lecture": lecture,
            "attendance": attendance_records,
            "present_count": sum(1 for record in attendance_records if record['status'] == 'present'),
            "absent_count": sum(1 for record in attendance_records if record['status'] == 'absent'),
            "total_students": len(attendance_records)
        }), 200
    except Error as e:
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

@app.route('/api/students/attendance', methods=['GET'])
@token_required
def get_student_attendance(current_user):
    if current_user['role'] != 'student':
        return jsonify({"error": "Only students can view their own attendance"}), 403
    
    course_id = request.args.get('course_id')
    if not course_id:
        return jsonify({"error": "Course ID is required"}), 400
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Verify student is enrolled in the course
        cursor.execute(
            "SELECT * FROM enrollments WHERE student_id = %s AND course_id = %s",
            (current_user['user_id'], course_id)
        )
        if not cursor.fetchone():
            return jsonify({"error": "You are not enrolled in this course"}), 403
        
        # Get all lectures for the course
        cursor.execute(
            """
            SELECT l.*, 
                   CASE WHEN a.attendance_id IS NOT NULL THEN 'present' ELSE 'absent' END as status,
                   a.timestamp as check_in_time
            FROM lectures l
            LEFT JOIN attendance a ON l.lecture_id = a.lecture_id AND a.student_id = %s
            WHERE l.course_id = %s
            ORDER BY l.date DESC, l.start_time DESC
            """,
            (current_user['user_id'], course_id)
        )
        lectures = cursor.fetchall()
        
        # Calculate attendance statistics
        total_lectures = len(lectures)
        attended_lectures = sum(1 for lecture in lectures if lecture['status'] == 'present')
        attendance_percentage = (attended_lectures / total_lectures * 100) if total_lectures > 0 else 0
        
        return jsonify({
            "lectures": lectures,
            "statistics": {
                "total_lectures": total_lectures,
                "attended_lectures": attended_lectures,
                "absent_lectures": total_lectures - attended_lectures,
                "attendance_percentage": round(attendance_percentage, 2)
            }
        }), 200
    except Error as e:
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

# Enrollment APIs
@app.route('/api/enrollments', methods=['POST'])
@token_required
def enroll_in_course(current_user):
    if current_user['role'] != 'student':
        return jsonify({"error": "Only students can enroll in courses"}), 403
    
    data = request.get_json()
    course_id = data.get('course_id')
    
    if not course_id:
        return jsonify({"error": "Course ID is required"}), 400
    
    conn = get_db_connection()
    if not conn:
        return jsonify({"error": "Database connection failed"}), 500
    
    cursor = conn.cursor(dictionary=True)
    try:
        # Check if course exists
        cursor.execute("SELECT * FROM courses WHERE course_id = %s", (course_id,))
        if not cursor.fetchone():
            return jsonify({"error": "Course not found"}), 404
        
        # Check if already enrolled
        cursor.execute(
            "SELECT * FROM enrollments WHERE student_id = %s AND course_id = %s",
            (current_user['user_id'], course_id)
        )
        if cursor.fetchone():
            return jsonify({"error": "You are already enrolled in this course"}), 400
        
        # Create enrollment
        cursor.execute(
            "INSERT INTO enrollments (student_id, course_id) VALUES (%s, %s)",
            (current_user['user_id'], course_id)
        )
        conn.commit()
        
        return jsonify({"message": "Enrolled successfully"}), 201
    except Error as e:
        conn.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        cursor.close()
        conn.close()

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5010)