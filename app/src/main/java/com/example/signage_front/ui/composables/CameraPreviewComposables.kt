package com.example.signage_front.ui.composables

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.signage_front.camera.AgeGenderClassifier
import com.example.signage_front.camera.FaceAnalysisResult
import com.example.signage_front.camera.FaceDetectionManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun FaceDetectionCameraPreview(
    onFaceAnalyzed: (FaceAnalysisResult) -> Unit,
    showPreview: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    // Request permission on start if not granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        // Run silently without crashes if camera permission is denied
        Box(
            modifier = modifier
                .size(1.dp)
                .background(Color.Transparent)
        )
        return
    }

    // Initialize Classifier, Manager, and Executor
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val classifier = remember(context) { AgeGenderClassifier(context) }
    val detectionManager = remember(classifier) { FaceDetectionManager(classifier) }

    // Keep the callback current without making it a binding key.
    val currentOnFaceAnalyzed by rememberUpdatedState(onFaceAnalyzed)

    // Collect flow values to expose to the callback
    val analysisResult by detectionManager.faceResult.collectAsState()

    LaunchedEffect(analysisResult) {
        currentOnFaceAnalyzed(analysisResult)
    }

    // The PreviewView is created by AndroidView; binding is driven by side effects
    // (not AndroidView.update) so ordinary recompositions never rebind the camera.
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // One-time teardown of the analysis pipeline.
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            detectionManager.close()
            classifier.close()
        }
    }

    // Bind exactly once per permission/lifecycle/view change, and unbind on dispose.
    DisposableEffect(hasCameraPermission, previewView, lifecycleOwner) {
        val pv = previewView
        if (!hasCameraPermission || pv == null) {
            return@DisposableEffect onDispose {}
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false

        val bindListener = Runnable {
            if (disposed) return@Runnable
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, detectionManager)
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera Binding failed: ${e.message}", e)
            }
        }
        cameraProviderFuture.addListener(bindListener, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopEnd
    ) {
        // Camera Preview setup (view only; binding is handled by the effect above)
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
            modifier = if (showPreview) {
                Modifier
                    .size(width = 160.dp, height = 220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            } else {
                // Keep the preview running headless/invisible to satisfy CameraX constraints on low-end hardware
                Modifier.size(1.dp)
            }
        )

        // Glassmorphic Info HUD over the preview card when preview is visible
        if (showPreview) {
            Box(
                modifier = Modifier
                    .size(width = 160.dp, height = 220.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(8.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (analysisResult.isFacePresent) "🎯 FACE DETECTED" else "🔍 SCANNING...",
                        color = if (analysisResult.isFacePresent) Color(0xFF4CAF50) else Color.White,
                        fontSize = 10.sp,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )

                    if (analysisResult.isFacePresent) {
                        analysisResult.gender?.let {
                            Text(
                                text = "Gender: $it",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                        analysisResult.age?.let {
                            Text(
                                text = "Age Estimate: $it",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        Text(
                            text = "Awaiting Audience...",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
