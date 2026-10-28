package com.verifydeviceinfo.data.android.collect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.telephony.TelephonyManager
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification

data class ConnectivityFacts(
    val wifiAvailable: Boolean,
    val bluetoothAvailable: Boolean,
    val nfcAvailable: Boolean,
    val gnssAvailable: Boolean,
    val telephonyAvailable: Boolean,
    val usbHostAvailable: Boolean,
    val fields: List<SpecField>
)

/** Network, radio and port capabilities. Nothing here requires a dangerous permission. */
class ConnectivityCollector(private val context: Context) {

    fun collect(): ConnectivityFacts {
        val fields = mutableListOf<SpecField>()
        val pm = context.packageManager

        val wifiAvailable = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val bluetoothAvailable = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val nfcAvailable = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
        val gnssAvailable = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
        val telephonyAvailable = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

        fields += FieldBuilder.value("net.wifi", "Wi-Fi hardware", if (wifiAvailable) "Present" else "Not present",
            SpecGroup.CONNECTIVITY, "PackageManager.FEATURE_WIFI")
        fields += FieldBuilder.value("net.bluetooth", "Bluetooth hardware",
            if (bluetoothAvailable) "Present" else "Not present", SpecGroup.CONNECTIVITY,
            "PackageManager.FEATURE_BLUETOOTH")
        fields += FieldBuilder.value("net.nfc", "NFC hardware", if (nfcAvailable) "Present" else "Not present",
            SpecGroup.CONNECTIVITY, "PackageManager.FEATURE_NFC")
        fields += FieldBuilder.value("net.gnss", "GNSS hardware", if (gnssAvailable) "Present" else "Not present",
            SpecGroup.CONNECTIVITY, "PackageManager.FEATURE_LOCATION_GPS")
        fields += FieldBuilder.value("net.telephony", "Cellular modem",
            if (telephonyAvailable) "Present" else "Not present", SpecGroup.CONNECTIVITY,
            "PackageManager.FEATURE_TELEPHONY")
        fields += FieldBuilder.value("net.usbHost", "USB host support",
            if (usbHost) "Supported" else "Not reported", SpecGroup.CONNECTIVITY,
            "PackageManager.FEATURE_USB_HOST")

        // Wi-Fi state (normal permission).
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                fields += FieldBuilder.value("net.wifiState", "Wi-Fi state",
                    if (wifiManager.isWifiEnabled) "Enabled" else "Disabled", SpecGroup.CONNECTIVITY,
                    "WifiManager.isWifiEnabled")
            }
        } catch (e: Exception) {
            fields += FieldBuilder.value("net.wifiState", "Wi-Fi state", null, SpecGroup.CONNECTIVITY,
                "WifiManager", note = "Wi-Fi state unreadable: ${e.message}")
        }

        // Bluetooth adapter state (needs BLUETOOTH_CONNECT from Android 12).
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                val state = try {
                    if (adapter.isEnabled) "Enabled" else "Disabled"
                } catch (security: SecurityException) {
                    null
                }
                fields += if (state != null) {
                    FieldBuilder.value("net.bluetoothState", "Bluetooth state", state,
                        SpecGroup.CONNECTIVITY, "BluetoothAdapter.isEnabled")
                } else {
                    FieldBuilder.value("net.bluetoothState", "Bluetooth state", null, SpecGroup.CONNECTIVITY,
                        "BluetoothAdapter.isEnabled",
                        note = "Requires the Nearby devices permission on Android 12 and newer.")
                }
                fields += FieldBuilder.value("net.bluetoothName", "Bluetooth adapter name",
                    try { adapter.name } catch (e: SecurityException) { null },
                    SpecGroup.CONNECTIVITY, "BluetoothAdapter.getName()", Confidence.MEDIUM,
                    sensitive = true)
            }
        } catch (e: Exception) {
            fields += FieldBuilder.value("net.bluetoothState", "Bluetooth state", null,
                SpecGroup.CONNECTIVITY, "BluetoothAdapter", note = "Unreadable: ${e.message}")
        }

        // NFC state.
        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
            fields += FieldBuilder.value("net.nfcState", "NFC state",
                when {
                    nfcAdapter == null -> "No NFC adapter"
                    nfcAdapter.isEnabled -> "Enabled"
                    else -> "Disabled"
                }, SpecGroup.CONNECTIVITY, "NfcAdapter.isEnabled")
        } catch (e: Exception) {
            fields += FieldBuilder.value("net.nfcState", "NFC state", null, SpecGroup.CONNECTIVITY,
                "NfcAdapter", note = "Unreadable: ${e.message}")
        }

        // Active network transport.
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val transports = mutableListOf<String>()
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "Wi-Fi"
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "Cellular"
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Ethernet"
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "VPN"
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports += "Bluetooth"
            }
            fields += FieldBuilder.value("net.activeTransport", "Active connection",
                transports.joinToString(", ").ifBlank { "None" }, SpecGroup.CONNECTIVITY,
                "ConnectivityManager.NetworkCapabilities")
            fields += FieldBuilder.value("net.internetCapable", "Internet capability",
                if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                    "Yes" else "No",
                SpecGroup.CONNECTIVITY, "NetworkCapabilities.NET_CAPABILITY_INTERNET", Confidence.MEDIUM)
        } catch (e: Exception) {
            fields += FieldBuilder.value("net.activeTransport", "Active connection", null,
                SpecGroup.CONNECTIVITY, "ConnectivityManager", note = "Unreadable: ${e.message}")
        }

        // Telephony / SIM.
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                val simState = when (tm.simState) {
                    TelephonyManager.SIM_STATE_READY -> "Ready"
                    TelephonyManager.SIM_STATE_ABSENT -> "Absent"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
                    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
                    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
                    TelephonyManager.SIM_STATE_UNKNOWN -> "Unknown"
                    else -> "State ${tm.simState}"
                }
                fields += FieldBuilder.value("net.simState", "SIM state", simState,
                    SpecGroup.CONNECTIVITY, "TelephonyManager.getSimState()")
                fields += FieldBuilder.value("net.operator", "Network operator",
                    tm.networkOperatorName?.takeIf { it.isNotBlank() }, SpecGroup.CONNECTIVITY,
                    "TelephonyManager.getNetworkOperatorName()")
                fields += FieldBuilder.value("net.phoneType", "Phone type",
                    when (tm.phoneType) {
                        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                        TelephonyManager.PHONE_TYPE_NONE -> "None"
                        else -> "Unknown"
                    }, SpecGroup.CONNECTIVITY, "TelephonyManager.getPhoneType()")
                fields += if (Build.VERSION.SDK_INT >= 30) {
                    FieldBuilder.value("net.radioType", "Data network type", null,
                        SpecGroup.CONNECTIVITY, "TelephonyManager.getDataNetworkType()",
                        note = "Requires the phone-state permission, which this app does not request for " +
                            "information collection.")
                } else {
                    FieldBuilder.unsupported(
                        "net.radioType", "Data network type", SpecGroup.CONNECTIVITY,
                        "TelephonyManager.getDataNetworkType()",
                        "Requires a privileged permission on modern Android."
                    )
                }
            }
        } catch (e: Exception) {
            fields += FieldBuilder.value("net.simState", "SIM state", null, SpecGroup.CONNECTIVITY,
                "TelephonyManager", note = "Unreadable: ${e.message}")
        }

        // Location providers (capability only, no location fix).
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val enabled = lm?.let {
                listOfNotNull(
                    "GPS".takeIf { _ -> it.isProviderEnabled(LocationManager.GPS_PROVIDER) },
                    "Network".takeIf { _ -> it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                )
            }.orEmpty()
            fields += FieldBuilder.value("net.locationProviders", "Enabled location providers",
                if (enabled.isEmpty()) "None enabled" else enabled.joinToString(", "),
                SpecGroup.CONNECTIVITY, "LocationManager.isProviderEnabled()",
                note = "Capability only — no location is read or stored.")
        } catch (e: Exception) {
            fields += FieldBuilder.value("net.locationProviders", "Enabled location providers", null,
                SpecGroup.CONNECTIVITY, "LocationManager", note = "Unreadable: ${e.message}")
        }

        return ConnectivityFacts(
            wifiAvailable = wifiAvailable,
            bluetoothAvailable = bluetoothAvailable,
            nfcAvailable = nfcAvailable,
            gnssAvailable = gnssAvailable,
            telephonyAvailable = telephonyAvailable,
            usbHostAvailable = usbHost,
            fields = fields
        )
    }
}
