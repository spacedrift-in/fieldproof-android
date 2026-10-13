package com.spacedrift.fieldproof.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spacedrift.fieldproof.R
import com.spacedrift.fieldproof.ui.components.SecureScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The camera's own lifecycle, independent of the navigation state. Modelled
 * explicitly so the screen never shows a blank frame: it is either still
 * spinning up, ready to shoot, or broken with a retry.
 */
private sealed interface CameraPhase {
    data object Starting : CameraPhase
    data object Ready : CameraPhase
    data class Failed(val reason: String) : CameraPhase
}

@Composable
fun CameraCaptureScreen(
    onPhotoCaptured: (Uri) -> Unit,
    onClose: () -> Unit,
    checklistItemTitle: String? = null,
    requiresPhoto: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Prevent screenshots/recording of live evidence capture.
    SecureScreen()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedUri by remember { mutableStateOf<Uri?>(null) }
    var cameraPhase by remember { mutableStateOf<CameraPhase>(CameraPhase.Starting) }
    var bindAttempt by remember { mutableStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val activity = remember(context) { context.findActivity() }
    var permissionRequested by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }

    fun refreshPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        showRationale =
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permissionRequested = true
            hasCameraPermission = granted
            showRationale =
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
        }

    // Re-read the permission on every resume so granting it in Settings (or in
    // the system dialog) recovers the flow immediately, without a relaunch.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        CameraPermissionScreen(
            permanentlyDenied = permissionRequested && !showRationale,
            onRequestPermission = {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenSettings = { context.openAppSettings() },
            onClose = onClose
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val previewUri = capturedUri
        if (previewUri != null) {
            CapturedPreview(
                uri = previewUri,
                onRetake = {
                    capturedUri = null
                    captureError = null
                },
                onUse = { onPhotoCaptured(previewUri) },
                onClose = onClose
            )
        } else {
            when (val phase = cameraPhase) {
                is CameraPhase.Failed -> {
                    CameraErrorScreen(
                        reason = phase.reason,
                        onRetry = {
                            cameraPhase = CameraPhase.Starting
                            bindAttempt += 1
                        },
                        onClose = onClose
                    )
                }

                else -> {
                    key(bindAttempt) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                val previewView = PreviewView(viewContext)
                                val cameraProviderFuture =
                                    ProcessCameraProvider.getInstance(viewContext)

                                cameraProviderFuture.addListener({
                                    try {
                                        val cameraProvider = cameraProviderFuture.get()
                                        val preview = Preview.Builder()
                                            .build()
                                            .also {
                                                it.surfaceProvider =
                                                    previewView.surfaceProvider
                                            }

                                        val capture = ImageCapture.Builder()
                                            .setCaptureMode(
                                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                            )
                                            .build()

                                        imageCapture = capture

                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            capture
                                        )
                                        cameraPhase = CameraPhase.Ready
                                    } catch (exception: Exception) {
                                        cameraPhase = CameraPhase.Failed(
                                            exception.message
                                                ?: "The camera could not be started."
                                        )
                                    }
                                }, ContextCompat.getMainExecutor(viewContext))

                                previewView
                            }
                        )
                    }

                    CaptureHeader(
                        title = checklistItemTitle,
                        requiresPhoto = requiresPhoto,
                        onClose = onClose,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(16.dp)
                    )

                    if (cameraPhase is CameraPhase.Starting) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("camera_starting"),
                                color = Color.White
                            )
                        }
                    }

                    CaptureControls(
                        capturing = capturing,
                        enabled = cameraPhase is CameraPhase.Ready && !capturing,
                        error = captureError,
                        onCapture = {
                            val capture = imageCapture
                            if (capture != null) {
                                capturing = true
                                captureError = null
                                takePhoto(
                                    context = context,
                                    imageCapture = capture,
                                    onCaptured = { uri ->
                                        capturing = false
                                        capturedUri = uri
                                    },
                                    onError = { message ->
                                        capturing = false
                                        captureError = message
                                    }
                                )
                            }
                        },
                        onClose = onClose,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureControls(
    capturing: Boolean,
    enabled: Boolean,
    error: String?,
    onCapture: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = onCapture,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (capturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Capturing…")
            } else {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Capture photo")
            }
        }

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }
    }
}

@Composable
internal fun CapturedPreview(
    uri: Uri,
    onRetake: () -> Unit,
    onUse: () -> Unit,
    onClose: () -> Unit
) {
    val bitmap = remember(uri) { decodeForPreview(uri) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.photo_preview_unavailable),
                    color = Color.White
                )
            }
        }

        CaptureHeader(
            title = stringResource(R.string.review_photo),
            requiresPhoto = false,
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRetake,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_retake))
                }

                Button(
                    onClick = onUse,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_use_photo))
                }
            }
        }
    }
}

@Composable
internal fun CaptureHeader(
    title: String?,
    requiresPhoto: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.size(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CAPTURE EVIDENCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = title ?: "General evidence",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    text = if (requiresPhoto) "Required photo" else "Optional evidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun CameraErrorScreen(
    reason: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.camera_error_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.camera_error_body, reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_try_again))
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_close))
        }
    }
}

@Composable
internal fun CameraPermissionScreen(
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    permanentlyDenied: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(
                if (permanentlyDenied) {
                    R.string.camera_permission_denied_body
                } else {
                    R.string.camera_permission_body
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = if (permanentlyDenied) onOpenSettings else onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    if (permanentlyDenied) {
                        R.string.action_open_settings
                    } else {
                        R.string.action_allow_camera
                    }
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_not_now))
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (Uri) -> Unit,
    onError: (String) -> Unit
) {
    // Capture to a private cache file first. It is sealed into the encrypted
    // evidence vault immediately after capture and the plaintext copy removed,
    // so no plaintext photo ever lands in backed-up external storage.
    val directory = File(context.cacheDir, "fieldproof_capture")

    if (!directory.exists()) {
        directory.mkdirs()
    }

    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    val photoFile = File(directory, "FIELDPROOF_$timestamp.jpg")

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {

            override fun onImageSaved(
                outputFileResults: ImageCapture.OutputFileResults
            ) {
                onCaptured(Uri.fromFile(photoFile))
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(
                    context,
                    "Photo capture failed: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
                onError(
                    context.getString(
                        R.string.camera_capture_failed,
                        exception.message ?: "unknown error"
                    )
                )
            }
        }
    )
}

private fun decodeForPreview(uri: Uri): Bitmap? {
    return try {
        val path = uri.path ?: return null
        if (!File(path).exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 1200 || bounds.outHeight / sampleSize > 1200) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(path, options)
    } catch (_: Exception) {
        null
    }
}
