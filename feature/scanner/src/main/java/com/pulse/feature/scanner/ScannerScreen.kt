package com.pulse.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.core.model.Display
import java.util.concurrent.Executors

/**
 * Barcode scanner.
 *
 * Visual design lands in Phase 7; what matters here is that every scan outcome
 * offers a next step, and that the camera is released properly.
 */
@Composable
fun ScannerScreen(
    onLogFood: (String) -> Unit,
    onCreateFood: (barcode: String, suggestedName: String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onPermissionResult,
    )

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        viewModel.onPermissionResult(granted)
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier.fillMaxSize()) {
        if (state.hasCameraPermission) {
            CameraPreview(
                onBarcode = viewModel::onBarcodeDetected,
                // Pausing analysis while a result is showing stops the camera
                // re-detecting the same product behind the result card.
                isActive = state.scan is ScanState.Scanning,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Denial is a normal path, not an error: manual entry still works.
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "PULSE uses the camera only to read barcodes. Nothing is recorded " +
                        "or uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
                TextButton(onClick = { onCreateFood("", null) }) {
                    Text("Add a food manually instead")
                }
            }
        }

        ResultPanel(
            state = state.scan,
            onLogFood = onLogFood,
            onCreateFood = onCreateFood,
            onRetry = viewModel::resumeScanning,
            onClose = onClose,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ResultPanel(
    state: ScanState,
    onLogFood: (String) -> Unit,
    onCreateFood: (String, String?) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ScanState.Scanning -> Unit

        is ScanState.Resolving -> Card(modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("Looking up ${state.barcode}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        is ScanState.Found -> Card(modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.food.food.name, style = MaterialTheme.typography.titleMedium)
                state.food.food.brand?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "${Display.kcal(state.food.food.kcalPer100)} kcal per 100 g",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onLogFood(state.food.food.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log this") }
                TextButton(onClick = onRetry) { Text("Scan another") }
            }
        }

        is ScanState.Incomplete -> Card(modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.food.food.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "We're missing some nutrition details for this one — worth " +
                        "checking before logging.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onLogFood(state.food.food.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Review and log") }
                TextButton(onClick = onRetry) { Text("Scan another") }
            }
        }

        is ScanState.NotFound -> Card(modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New product", style = MaterialTheme.typography.titleMedium)
                Text(
                    "We don't know ${state.barcode} yet. Add it once and it'll be " +
                        "here instantly next time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onCreateFood(state.barcode, state.suggestedName) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add it") }
                TextButton(onClick = onRetry) { Text("Scan another") }
            }
        }

        is ScanState.Unavailable -> Card(modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't check online", style = MaterialTheme.typography.titleMedium)
                // Careful wording: this is NOT "product doesn't exist". We'll
                // retry the lookup in the background when there's a connection.
                Text(
                    "We'll look ${state.barcode} up again when you're back online. " +
                        "You can add it yourself in the meantime.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onCreateFood(state.barcode, null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add it manually") }
                TextButton(onClick = onRetry) { Text("Scan another") }
            }
        }

        ScanState.Unreadable -> Card(modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "That doesn't look like a product barcode — try again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRetry) { Text("Keep scanning") }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onBarcode: (String) -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // A dedicated single thread: analysis must not run on the main thread, and
    // one frame at a time is enough with STRATEGY_KEEP_ONLY_LATEST.
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { BarcodeAnalyzer(onBarcode = onBarcode) }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.close()
            executor.shutdown()
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) analyzer.reset()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analysis = ImageAnalysis.Builder()
                    // Drop stale frames rather than queueing them: a backlog
                    // makes the preview lag badly on older phones, and an old
                    // frame is worthless for scanning anyway.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}
