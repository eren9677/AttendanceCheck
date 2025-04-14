package com.example.attendancecheck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.attendancecheck.api.ApiService
import com.example.attendancecheck.databinding.ActivityQrScannerBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class QRScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var apiService: ApiService
    private var courseId: Int = 0
    private var courseName: String = ""
    private var courseCode: String = ""
    private var isScanningEnabled = true
    
    companion object {
        const val REQUEST_CODE_SCAN = 1001
        const val CAMERA_PERMISSION_REQUEST_CODE = 200
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "QR Scanner"
        
        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        apiService = retrofit.create(ApiService::class.java)
        
        // Get course data from intent
        courseId = intent.getIntExtra("COURSE_ID", 0)
        courseName = intent.getStringExtra("COURSE_NAME") ?: ""
        courseCode = intent.getStringExtra("COURSE_CODE") ?: ""
        
        // Update UI with course info
        binding.tvCourseName.text = "$courseCode: $courseName"
        
        // Check camera permission
        if (checkCameraPermission()) {
            setupBarcodeScanner()
        } else {
            requestCameraPermission()
        }
    }
    
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            // Show explanation dialog
            AlertDialog.Builder(this)
                .setTitle("Camera Permission Required")
                .setMessage("This app needs camera access to scan QR codes for attendance.")
                .setPositiveButton("Grant") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.CAMERA),
                        CAMERA_PERMISSION_REQUEST_CODE
                    )
                }
                .setNegativeButton("Cancel") { _, _ ->
                    finish()
                }
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupBarcodeScanner()
            } else {
                Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun setupBarcodeScanner() {
        try {
            // Create barcode scanner view
            barcodeView = DecoratedBarcodeView(this)
            
            // Configure camera settings
            val cameraSettings = barcodeView.cameraSettings
            cameraSettings.isAutoFocusEnabled = true
            cameraSettings.isContinuousFocusEnabled = true
            cameraSettings.isBarcodeSceneModeEnabled = true
            
            // Set up decoder factory
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            barcodeView.statusView?.visibility = View.GONE
            
            // Add to container
            binding.barcodeScannerContainer.addView(barcodeView)
            
            // Set callback
            barcodeView.decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    if (isScanningEnabled) {
                        // Disable further scanning until this one completes
                        isScanningEnabled = false
                        
                        // Process the QR code result
                        val qrToken = result.text
                        processQRCode(qrToken)
                    }
                }
                
                override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
                    // Not needed
                }
            })
            
            // Show initial instructions
            binding.tvStatus.text = "Position the QR code within the frame"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            
            // Start camera preview
            barcodeView.resume()
            
        } catch (e: Exception) {
            showError("Failed to initialize camera: ${e.message}")
        }
    }
    
    private fun processQRCode(token: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Processing attendance..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        
        lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences("AttendanceCheck", MODE_PRIVATE)
                val authToken = prefs.getString("token", null)
                
                if (authToken == null) {
                    showError("Authentication failed. Please log in again.")
                    finish()
                    return@launch
                }
                
                val response = apiService.checkInAttendance(
                    "Bearer $authToken",
                    mapOf("token" to token)
                )
                
                if (response.isSuccessful) {
                    // Success
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "✅ Attendance recorded successfully!"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@QRScannerActivity, android.R.color.holo_green_dark))
                    
                    // Show success UI and return to dashboard after delay
                    binding.tvScanInstruction.text = "Attendance Recorded"
                    
                    // Return to dashboard after 2 seconds
                    binding.root.postDelayed({
                        setResult(RESULT_OK)
                        finish()
                    }, 2000)
                    
                } else {
                    // Error
                    showError("Failed to record attendance: ${response.message()}")
                    isScanningEnabled = true // Allow retry
                }
                
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                isScanningEnabled = true // Allow retry
            }
        }
    }
    
    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "❌ $message"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    override fun onResume() {
        super.onResume()
        if (checkCameraPermission()) {
            barcodeView.resume()
        }
    }
    
    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        barcodeView.pause()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
