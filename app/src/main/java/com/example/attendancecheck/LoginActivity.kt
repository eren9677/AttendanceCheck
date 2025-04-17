package com.example.attendancecheck

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.attendancecheck.api.ApiService
import com.example.attendancecheck.api.LoginRequest
import com.example.attendancecheck.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)

        // Set up click listeners
        binding.btnLogin.setOnClickListener {
            val universityId = binding.etUniversityId.text.toString()
            val password = binding.etPassword.text.toString()

            if (universityId.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            login(universityId, password)
        }

        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    private fun login(universityId: String, password: String) {
        lifecycleScope.launch {
            try {
                val response = apiService.login(LoginRequest(universityId, password))
                if (response.isSuccessful) {
                    val loginResponse = response.body()
                    if (loginResponse != null) {
                        // Save token and user info to SharedPreferences
                        val sharedPrefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                        sharedPrefs.edit()
                            .putString("token", loginResponse.token)
                            .putString("user_role", loginResponse.user.role)
                            .putInt("user_id", loginResponse.user.user_id)
                            .putString("user_name", loginResponse.user.name)
                            .putString("university_id", loginResponse.user.university_id)
                            .apply()

                        // Log successful login for debugging
                        Log.d("LoginActivity", "User logged in: ID=${loginResponse.user.user_id}, Role=${loginResponse.user.role}")

                        // Navigate to appropriate dashboard based on role
                        val intent = when (loginResponse.user.role) {
                            "student" -> Intent(this@LoginActivity, StudentDashboardActivity::class.java)
                            "lecturer" -> Intent(this@LoginActivity, LecturerDashboardActivity::class.java)
                            else -> throw IllegalStateException("Invalid user role")
                        }
                        startActivity(intent)
                        finish()
                    }
                } else {
                    Toast.makeText(this@LoginActivity, "Login failed: ${response.message()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
} 