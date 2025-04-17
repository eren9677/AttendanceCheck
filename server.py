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
            # For lecturers, get their courses with student counts and active QR codes
            cursor.execute(
                """
                SELECT c.*, u.name as lecturer_name,
                       (SELECT COUNT(*) FROM enrollments e WHERE e.course_id = c.course_id) as student_count,
                       (SELECT COUNT(*) > 0 FROM qr_codes qr 
                        JOIN lectures l ON qr.lecture_id = l.lecture_id 
                        WHERE l.course_id = c.course_id AND qr.expires_at > NOW()) as has_active_qr,
                       (SELECT TIMESTAMPDIFF(SECOND, NOW(), qr.expires_at) 
                        FROM qr_codes qr 
                        JOIN lectures l ON qr.lecture_id = l.lecture_id 
                        WHERE l.course_id = c.course_id AND qr.expires_at > NOW() 
                        ORDER BY qr.expires_at DESC LIMIT 1) as qr_remaining_seconds
                FROM courses c
                JOIN users u ON c.lecturer_id = u.user_id
                WHERE c.lecturer_id = %s
                """,
                (current_user['user_id'],)
            )
        else:  # student
            # For students, get their enrolled courses with lecturer names and active QR codes
            cursor.execute(
                """
                SELECT c.*, u.name as lecturer_name,
                       (SELECT COUNT(*) > 0 FROM qr_codes qr 
                        JOIN lectures l ON qr.lecture_id = l.lecture_id 
                        WHERE l.course_id = c.course_id AND qr.expires_at > NOW()) as has_active_qr,
                       (SELECT TIMESTAMPDIFF(SECOND, NOW(), qr.expires_at) 
                        FROM qr_codes qr 
                        JOIN lectures l ON qr.lecture_id = l.lecture_id 
                        WHERE l.course_id = c.course_id AND qr.expires_at > NOW() 
                        ORDER BY qr.expires_at DESC LIMIT 1) as qr_remaining_seconds
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
        # Get all courses except those the student is already enrolled in, including active QR codes
        cursor.execute(
            """
            SELECT c.*, u.name as lecturer_name,
                   (SELECT COUNT(*) > 0 FROM qr_codes qr 
                    JOIN lectures l ON qr.lecture_id = l.lecture_id 
                    WHERE l.course_id = c.course_id AND qr.expires_at > NOW()) as has_active_qr,
                   (SELECT TIMESTAMPDIFF(SECOND, NOW(), qr.expires_at) 
                    FROM qr_codes qr 
                    JOIN lectures l ON qr.lecture_id = l.lecture_id 
                    WHERE l.course_id = c.course_id AND qr.expires_at > NOW() 
                    ORDER BY qr.expires_at DESC LIMIT 1) as qr_remaining_seconds
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

@app.route('/api/courses/<int:course_id>', methods=['DELETE'])
@token_required
def delete_course(current_user, course_id):
    if current_user['role'] != 'lecturer':
        return jsonify({"error": "Only lecturers can delete courses"}), 403
    
    print(f"Attempting to delete course ID: {course_id} by lecturer ID: {current_user['user_id']}")
    
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
        course = cursor.fetchone()
        if not course:
            print(f"Course not found or unauthorized: {course_id}")
            return jsonify({"error": "Course not found or you don't have permission"}), 404
        
        print(f"Found course to delete: {course['course_code']} - {course['course_name']}")
        
        # Get all lectures for this course
        cursor.execute("SELECT lecture_id FROM lectures WHERE course_id = %s", (course_id,))
        lectures = cursor.fetchall()
        lecture_ids = [lecture['lecture_id'] for lecture in lectures]
        
        print(f"Found {len(lecture_ids)} lectures to delete")
        
        # No need to explicitly start a transaction - MySQL connector automatically uses transactions
        # and we'll commit at the end or rollback on error
        
        # Delete attendance records first (depends on lectures and qr_codes)
        if lecture_ids:
            lecture_ids_str = ','.join(['%s'] * len(lecture_ids))
            cursor.execute(
                f"DELETE FROM attendance WHERE lecture_id IN ({lecture_ids_str})",
                lecture_ids
            )
            print(f"Deleted {cursor.rowcount} attendance records")
        
        # Delete QR codes (depends on lectures)
        if lecture_ids:
            lecture_ids_str = ','.join(['%s'] * len(lecture_ids))
            cursor.execute(
                f"DELETE FROM qr_codes WHERE lecture_id IN ({lecture_ids_str})",
                lecture_ids
            )
            print(f"Deleted {cursor.rowcount} QR codes")
        
        # Delete lectures (depends on course)
        cursor.execute("DELETE FROM lectures WHERE course_id = %s", (course_id,))
        print(f"Deleted {cursor.rowcount} lectures")
        
        # Delete enrollments (depends on course)
        cursor.execute("DELETE FROM enrollments WHERE course_id = %s", (course_id,))
        print(f"Deleted {cursor.rowcount} enrollments (unenrolled students)")
        
        # Finally, delete the course
        cursor.execute("DELETE FROM courses WHERE course_id = %s", (course_id,))
        print(f"Deleted course {course_id}")
        
        # Commit the transaction
        conn.commit()
        
        print(f"Course deletion complete: {course_id}")
        return jsonify({"message": "Course and all related data deleted successfully"}), 200
    except Exception as e:
        # Roll back the transaction if any error occurs
        conn.rollback()
        print(f"Error in delete_course: {str(e)}")
        import traceback
        traceback.print_exc()
        return jsonify({"error": f"Failed to delete course: {str(e)}"}), 500
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
@app.route('/api/courses/<int:course_id>/qrcode', methods=['POST'])
@token_required
def generate_course_qr(current_user, course_id):
    if current_user['role'] != 'lecturer':
        return jsonify({"error": "Only lecturers can generate QR codes"}), 403
    
    data = request.get_json()
    expiry_minutes = data.get('expiry_minutes', 15)  # Default to 15 minutes
    
    print(f"Generating QR code for course {course_id} with {expiry_minutes} minutes validity")
    
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
        
        # Create a lecture for today
        today = datetime.datetime.now().date()
        current_time = datetime.datetime.now().time()
        end_time = (datetime.datetime.now() + datetime.timedelta(minutes=expiry_minutes)).time()
        
        print(f"Creating lecture for today {today} from {current_time} to {end_time}")
        
        cursor.execute(
            "INSERT INTO lectures (course_id, date, start_time, end_time) VALUES (%s, %s, %s, %s)",
            (course_id, today, current_time, end_time)
        )
        conn.commit()
        lecture_id = cursor.lastrowid
        
        print(f"Created lecture with ID: {lecture_id}")
        
        # Generate a unique token
        token = str(uuid.uuid4())
        print(f"Generated token: {token}")
        
        # Calculate expiry time
        expires_at = datetime.datetime.now() + datetime.timedelta(minutes=expiry_minutes)
        
        # Save QR code info to database
        cursor.execute(
            "INSERT INTO qr_codes (lecture_id, token, expires_at) VALUES (%s, %s, %s)",
            (lecture_id, token, expires_at)
        )
        conn.commit()
        qr_id = cursor.lastrowid
        
        print(f"Saved QR code info with ID: {qr_id}")
        
        try:
            # Generate QR code
            print("Starting QR code generation...")
            qr = qrcode.QRCode(
                version=1,
                error_correction=qrcode.constants.ERROR_CORRECT_L,
                box_size=10,
                border=4,
            )
            qr.add_data(token)
            qr.make(fit=True)
            
            print("QR code generated, creating image...")
            img = qr.make_image(fill_color="black", back_color="white")
            buffered = BytesIO()
            img.save(buffered)
            img_str = base64.b64encode(buffered.getvalue()).decode()
            print("QR code image created successfully")
            
            # Calculate remaining time in seconds
            remaining_seconds = int((expires_at - datetime.datetime.now()).total_seconds())
            
            return jsonify({
                "qr_id": qr_id,
                "token": token,
                "expires_at": expires_at.isoformat(),
                "remaining_seconds": remaining_seconds,
                "qr_image": f"data:image/png;base64,{img_str}"
            }), 200
        except Exception as e:
            print(f"Error during QR code generation: {str(e)}")
            raise
    except Error as e:
        conn.rollback()
        print(f"Database error: {str(e)}")  # Log the error
        return jsonify({"error": f"Database error: {str(e)}"}), 500
    except Exception as e:
        conn.rollback()
        print(f"Unexpected error: {str(e)}")  # Log the error
        return jsonify({"error": f"Unexpected error: {str(e)}"}), 500
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

@app.route('/api/courses/<int:course_id>/attendance', methods=['GET'])
@token_required
def get_course_attendance(current_user, course_id):
    """Get attendance data for all students in a course across all dates."""
    if current_user['role'] != 'lecturer':
        return jsonify({"error": "Only lecturers can view attendance reports"}), 403
    
    print(f"Fetching attendance data for course ID: {course_id}, requested by user ID: {current_user['user_id']}")
    
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
        course = cursor.fetchone()
        if not course:
            print(f"Course not found or unauthorized: {course_id}")
            return jsonify({"error": "Course not found or you don't have permission"}), 404
        
        print(f"Course found: {course['course_code']} - {course['course_name']}")
        
        # Get all students enrolled in the course
        cursor.execute(
            """
            SELECT u.user_id, u.name as student_name, u.university_id as student_id
            FROM users u
            JOIN enrollments e ON u.user_id = e.student_id
            WHERE e.course_id = %s AND u.role = 'student'
            ORDER BY u.name
            """,
            (course_id,)
        )
        students = cursor.fetchall()
        
        print(f"Found {len(students)} students enrolled in the course")
        
        if not students:
            return jsonify({
                "students": [],
                "dates": []
            }), 200
        
        # Get all lectures for this course
        cursor.execute(
            """
            SELECT lecture_id, DATE_FORMAT(date, '%Y-%m-%d') as lecture_date
            FROM lectures
            WHERE course_id = %s
            ORDER BY date DESC
            """,
            (course_id,)
        )
        lectures = cursor.fetchall()
        
        print(f"Found {len(lectures)} lectures for the course")
        
        if not lectures:
            return jsonify({
                "students": [],
                "dates": []
            }), 200
        
        # Extract unique dates
        dates = list(set(lecture['lecture_date'] for lecture in lectures))
        dates.sort(reverse=True)  # Most recent dates first
        
        print(f"Found {len(dates)} unique lecture dates")
        
        # Get all attendance records for this course in one query for efficiency
        cursor.execute(
            """
            SELECT a.student_id, l.lecture_id, DATE_FORMAT(l.date, '%Y-%m-%d') as lecture_date
            FROM attendance a
            JOIN lectures l ON a.lecture_id = l.lecture_id
            WHERE l.course_id = %s
            """,
            (course_id,)
        )
        all_attendance = cursor.fetchall()
        
        print(f"Found {len(all_attendance)} attendance records")
        
        # Create a lookup map for quick access
        attendance_lookup = {}
        for record in all_attendance:
            student_id = record['student_id']
            date = record['lecture_date']
            
            if student_id not in attendance_lookup:
                attendance_lookup[student_id] = set()
            
            attendance_lookup[student_id].add(date)
        
        # Format the final response
        formatted_students = []
        for student in students:
            student_id = student['user_id']
            attendance_dates = attendance_lookup.get(student_id, set())
            
            # Create attendance map for this student
            attendance_data = {}
            for date in dates:
                attendance_data[date] = date in attendance_dates
            
            formatted_student = {
                'student_id': student['student_id'],
                'student_name': student['student_name'],
                'attendance': attendance_data
            }
            
            formatted_students.append(formatted_student)
            
            # Debug print
            print(f"Student: {student['student_name']} has {len(attendance_dates)} attendance records")
        
        result = {
            "students": formatted_students,
            "dates": dates
        }
        
        print(f"Returning attendance data with {len(formatted_students)} students and {len(dates)} dates")
        
        return jsonify(result), 200
    except Exception as e:
        print(f"Error in get_course_attendance: {str(e)}")
        import traceback
        traceback.print_exc()
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