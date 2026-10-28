package com.verifydeviceinfo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verifydeviceinfo.ui.AppNav
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.theme.VerifyDeviceTheme

class MainActivity : ComponentActivity() {

    private var permissionBridge: com.verifydeviceinfo.data.android.AndroidPermissionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VerifyDeviceTheme {
                val viewModel: AppViewModel = viewModel()
                permissionBridge = viewModel.container.permissionManager.also {
                    it.activityProvider = { this }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNav(viewModel = viewModel)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionBridge?.onRequestPermissionsResult(requestCode, grantResults)
    }
}
