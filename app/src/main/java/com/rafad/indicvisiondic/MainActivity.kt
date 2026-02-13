package com.rafad.indicvisiondic

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: View
    private lateinit var tvResults: TextView
    private lateinit var btnReset: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // DIC State
    private var isReferenceSet = false
    private var roiRect: Rect? = null
    private var subsetSize = 61
    private var lastU = 0.0
    private var lastV = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        overlayView = findViewById(R.id.overlayView)
        tvResults = findViewById(R.id.tvResults)
        btnReset = findViewById(R.id.btnReset)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(Manifest.permission.CAMERA)
        }

        setupTouchListener()

        btnReset.setOnClickListener {
            isReferenceSet = false
            roiRect = null
            lastU = 0.0
            lastV = 0.0
            tvResults.text = "Tap to select ROI"
            overlayView.foreground = null
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        processImage(imageProxy)
                    })
                }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("IndicVision", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImage(imageProxy: ImageProxy) {
        // --- COMMENTED OUT FOR STATIC ANALYSIS MODE ---
        // This prevents the build error since we removed initReference/processFrame
        // from the Native Library to focus on the Gallery Feature.

        /*
        val buffer = imageProxy.planes[0].buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val currentRoi = roiRect

        if (currentRoi != null) {
             if (!isReferenceSet) {
                IndicVisionNativeLib.initReference(width, height, buffer, currentRoi.left, currentRoi.top, subsetSize)
                isReferenceSet = true
            } else {
                val results = IndicVisionNativeLib.processFrame(width, height, buffer, lastU, lastV)
                // ... processing results ...
            }
        }
        */

        imageProxy.close()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        overlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (!isReferenceSet) {
                    val x = event.x.toInt()
                    val y = event.y.toInt()
                    val boxSize = 100
                    roiRect = Rect(x - boxSize/2, y - boxSize/2, x + boxSize/2, y + boxSize/2)
                    drawRectOnOverlay(roiRect!!)
                    tvResults.text = "ROI Selected (Camera Mode Disabled)"
                }
            }
            true
        }
    }

    private fun drawRectOnOverlay(rect: Rect) {
        val drawable = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape())
        drawable.paint.color = Color.RED
        drawable.paint.style = Paint.Style.STROKE
        drawable.paint.strokeWidth = 5f
        drawable.bounds = rect
        overlayView.foreground = drawable
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) { startCamera() } else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show() }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}