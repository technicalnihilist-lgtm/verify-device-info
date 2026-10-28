package com.verifydeviceinfo.ui.screens.tests

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.AppPermission
import com.verifydeviceinfo.core.model.DiagnosticResult
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.PermissionState
import com.verifydeviceinfo.data.android.diagnostics.CameraProbe
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.AppScreen
import com.verifydeviceinfo.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun CameraTestScreen(
    testId: String,
    state: UiState,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val probe = remember { CameraProbe(context) }
    val cameraId = remember { resolveCameraId(context, testId) }
    var granted by remember {
        mutableStateOf(viewModel.container.permissionManager.isGranted(AppPermission.CAMERA))
    }
    var error by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    val title = when (testId) {
        "camera_rear" -> "Rear camera"
        "camera_front" -> "Front camera"
        else -> "Flashlight"
    }

    DisposableEffect(Unit) {
        onDispose {
            probe.close()
            if (torchOn) probe.setTorch(cameraId ?: "", false)
        }
    }

    AppScreen(title = title, onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            if (cameraId == null) {
                SectionCard(title = "Not available") {
                    Text(
                        "No matching camera was enumerated by the camera service.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.NOT_SUPPORTED,
                                note = "The camera service did not enumerate a matching module."
                            )
                        )
                        onBack()
                    }) { Text("Record as not supported") }
                }
                return@AppScreen
            }

            if (!granted) {
                SectionCard(
                    title = "Permission required",
                    subtitle = "The camera permission is needed to open a preview. Nothing is captured " +
                        "or stored — the preview is shown live and never saved."
                ) {
                    Button(onClick = {
                        scope.launch {
                            granted = viewModel.container.permissionManager
                                .request(AppPermission.CAMERA) == PermissionState.GRANTED
                            if (!granted) {
                                viewModel.recordResult(
                                    DiagnosticResult(
                                        testId = testId,
                                        title = title,
                                        status = DiagnosticStatus.PERMISSION_REQUIRED,
                                        note = "The camera permission was not granted, so the test was not run."
                                    )
                                )
                            }
                        }
                    }) { Text("Grant camera permission") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
                return@AppScreen
            }

            if (testId == "flashlight") {
                SectionCard(
                    title = "Torch control",
                    subtitle = "Switches the rear flash LED directly through the camera service."
                ) {
                    Button(onClick = {
                        torchOn = !torchOn
                        val ok = probe.setTorch(cameraId, torchOn)
                        if (!ok) error = "The camera service refused the torch request."
                    }) { Text(if (torchOn) "Turn flash off" else "Turn flash on") }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        probe.open(cameraId, holder.surface) { message -> error = message }
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int
                                    ) = Unit

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        probe.close()
                                    }
                                })
                            }
                        }
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionCard(title = "What to check") {
                Text(
                    if (testId == "flashlight") {
                        "The flash should light up immediately and turn off cleanly."
                    } else {
                        "Look for a sharp, correctly oriented image with no blotches, lines, haze or " +
                            "fixed dark spots. Cover the lens briefly — the preview should react."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        probe.close()
                        if (torchOn) probe.setTorch(cameraId, false)
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = if (error == null) DiagnosticStatus.PASS else DiagnosticStatus.FAILED,
                                measured = error ?: "Preview opened successfully",
                                note = if (error == null) {
                                    "Confirmed by the user."
                                } else {
                                    "The camera service reported an error."
                                }
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Works") }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(
                    onClick = {
                        probe.close()
                        if (torchOn) probe.setTorch(cameraId, false)
                        viewModel.recordResult(
                            DiagnosticResult(
                                testId = testId,
                                title = title,
                                status = DiagnosticStatus.FAILED,
                                note = "The user reported that this component does not work."
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Faulty") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun resolveCameraId(context: Context, testId: String): String? {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    val wanted = if (testId == "camera_front") CameraCharacteristics.LENS_FACING_FRONT
    else CameraCharacteristics.LENS_FACING_BACK
    return try {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
        }
    } catch (e: Exception) {
        null
    }
}
