package com.example.signage_front.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FaceAnalysisResult(
    val isFacePresent: Boolean,
    val gender: String? = null,
    val age: Int? = null,
    val confidence: Float? = null
)

class FaceDetectionManager(
    private val ageGenderClassifier: AgeGenderClassifier
) : ImageAnalysis.Analyzer {

    private val TAG = "FaceDetectionManager"

    private val _faceResult = MutableStateFlow(FaceAnalysisResult(false))
    val faceResult: StateFlow<FaceAnalysisResult> = _faceResult.asStateFlow()

    private var lastAnalyzedTimestamp = 0L
    private val THROTTLE_INTERVAL_MS = 1500L // Run once every 1.5 seconds to save power

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalyzedTimestamp < THROTTLE_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalyzedTimestamp = currentTime

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val primaryFace = faces[0]
                    val boundingBox = primaryFace.boundingBox

                    try {
                        // Convert ImageProxy frame to Bitmap using CameraX built-in toBitmap()
                        val fullBitmap = imageProxy.toBitmap()
                        
                        // Crop the face area safely
                        val left = boundingBox.left.coerceIn(0, fullBitmap.width - 1)
                        val top = boundingBox.top.coerceIn(0, fullBitmap.height - 1)
                        val width = boundingBox.width().coerceIn(1, fullBitmap.width - left)
                        val height = boundingBox.height().coerceIn(1, fullBitmap.height - top)

                        val croppedFace = Bitmap.createBitmap(fullBitmap, left, top, width, height)

                        // Run age and gender classifier (uses TFLite or fallback)
                        val trackingId = if (primaryFace.trackingId != -1) primaryFace.trackingId else null
                        val (age, gender) = ageGenderClassifier.predict(croppedFace, trackingId)

                        _faceResult.value = FaceAnalysisResult(
                            isFacePresent = true,
                            gender = gender,
                            age = age,
                            confidence = 1.0f
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cropping face or predicting age/gender: ${e.message}", e)
                        // Emitting partial success (face detected, details unknown)
                        _faceResult.value = FaceAnalysisResult(
                            isFacePresent = true,
                            gender = "Unknown",
                            age = null,
                            confidence = 0.5f
                        )
                    }
                } else {
                    _faceResult.value = FaceAnalysisResult(isFacePresent = false)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection model failed: ${e.message}", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
