package com.verifydeviceinfo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verifydeviceinfo.data.android.AndroidPermissionManager
import com.verifydeviceinfo.ui.AppNav
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.theme.VerifyDeviceTheme

class MainActivity : ComponentActivity() {

    private var permissionBridge: AndroidPermissionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VerifyDeviceTheme {
                val viewModel: AppViewModel = viewModel()
                // The manager is owned by the process-scoped container, so it is told which activity
                // to request from and is detached again in onDestroy. Never hold `this` in it.
                val manager = viewModel.container.permissionManager
                if (permissionBridge !== manager) {
                    permissionBridge = manager
                    manager.attach(this)
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

    override fun onDestroy() {
        permissionBridge?.detach(this)
        permissionBridge = null
        super.onDestroy()
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
