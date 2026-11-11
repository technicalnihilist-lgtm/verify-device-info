package com.verifydeviceinfo.data.android.collect

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
            val adapter = manager?.adapter
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
                    attempt { adapter.name }.value,
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
            val internetCapable = attempt {
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            fields += if (internetCapable.failure != null) {
                FieldBuilder.value("net.internetCapable", "Internet capability", null,
                    SpecGroup.CONNECTIVITY, "NetworkCapabilities.NET_CAPABILITY_INTERNET",
                    Confidence.MEDIUM, note = unreadableNote(internetCapable.failure))
            } else {
                FieldBuilder.value("net.internetCapable", "Internet capability",
                    if (internetCapable.value == true) "Yes" else "No",
                    SpecGroup.CONNECTIVITY, "NetworkCapabilities.NET_CAPABILITY_INTERNET", Confidence.MEDIUM)
            }
        } catch (e: Exception) {
            fields += FieldBuilder.value("net.activeTransport", "Active connection", null,
                SpecGroup.CONNECTIVITY, "ConnectivityManager", note = "Unreadable: ${e.message}")
        }

        // Telephony / SIM.
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                val simStateRead = attempt { tm.simState }
                val simState = simStateRead.value?.let { state ->
                    when (state) {
                        TelephonyManager.SIM_STATE_READY -> "Ready"
                        TelephonyManager.SIM_STATE_ABSENT -> "Absent"
                        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
                        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
                        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
                        TelephonyManager.SIM_STATE_UNKNOWN -> "Unknown"
                        else -> "State $state"
                    }
                }
                fields += if (simStateRead.failure != null) {
                    FieldBuilder.value("net.simState", "SIM state", null,
                        SpecGroup.CONNECTIVITY, "TelephonyManager.getSimState()",
                        note = unreadableNote(simStateRead.failure))
                } else {
                    FieldBuilder.value("net.simState", "SIM state", simState,
                        SpecGroup.CONNECTIVITY, "TelephonyManager.getSimState()")
                }
                val operatorRead = attempt { tm.networkOperatorName }
                fields += if (operatorRead.failure != null) {
                    FieldBuilder.value("net.operator", "Network operator", null,
                        SpecGroup.CONNECTIVITY, "TelephonyManager.getNetworkOperatorName()",
                        note = unreadableNote(operatorRead.failure))
                } else {
                    FieldBuilder.value("net.operator", "Network operator",
                        operatorRead.value?.takeIf { it.isNotBlank() }, SpecGroup.CONNECTIVITY,
                        "TelephonyManager.getNetworkOperatorName()")
                }
                val phoneTypeRead = attempt { tm.phoneType }
                fields += if (phoneTypeRead.failure != null) {
                    FieldBuilder.value("net.phoneType", "Phone type", null,
                        SpecGroup.CONNECTIVITY, "TelephonyManager.getPhoneType()",
                        note = unreadableNote(phoneTypeRead.failure))
                } else {
                    FieldBuilder.value("net.phoneType", "Phone type",
                        when (phoneTypeRead.value) {
                            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                            TelephonyManager.PHONE_TYPE_NONE -> "None"
                            else -> "Unknown"
                        }, SpecGroup.CONNECTIVITY, "TelephonyManager.getPhoneType()")
                }
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
            // Read one provider at a time. `isProviderEnabled` throws IllegalArgumentException for a
            // provider the platform does not publish — a TV box has no GPS provider — and the two
            // reads used to sit inside one `listOfNotNull`, so the absent provider's exception took
            // the other provider's reading with it and labelled the whole row unreadable. See
            // [locationProviderRow].
            val gps = if (lm == null) Reading<Boolean>(null, null) else attempt {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }
            val network = if (lm == null) Reading<Boolean>(null, null) else attempt {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            val row = locationProviderRow(gps, network)
            fields += FieldBuilder.value("net.locationProviders", "Enabled location providers",
                row.value, SpecGroup.CONNECTIVITY, "LocationManager.isProviderEnabled()",
                note = row.note ?: "Capability only — no location is read or stored.")
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

/**
 * One optional framework read: the value it produced, or the exception that stopped it.
 *
 * The connectivity section is a list of independent rows, but three of its blocks read several
 * values inside one `try`. A throw in the middle of such a block used to cost everything it had
 * already read — the enclosing `catch` replaced the whole block with one "Unreadable" row — and it
 * re-emitted a key that had already been added, so the snapshot carried the same [SpecField] key
 * twice and the export listed two different values for one property. Reading one value at a time
 * through [attempt] keeps the rows that did work and makes a duplicate key impossible, because each
 * key is now emitted exactly once, by the branch that owns it.
 */
internal class Reading<out T>(val value: T?, val failure: Exception?)

/** Runs [block], turning any exception into a [Reading] that carries the reason instead. */
internal inline fun <T> attempt(block: () -> T): Reading<T> = try {
    Reading(block(), null)
} catch (e: Exception) {
    Reading(null, e)
}

/** The note a row carries when its read failed, in the wording the blocks already used. */
internal fun unreadableNote(failure: Exception): String = "Unreadable: ${failure.message}"

/** A row's rendered value and, when it could not be measured, the note that says why. */
internal class ProviderRow(val value: String?, val note: String?)

/**
 * The "Enabled location providers" row, from the GPS and network provider reads.
 *
 * `LocationManager.isProviderEnabled` throws `IllegalArgumentException` when the platform does not
 * publish the provider at all — a device with no GNSS, such as a TV box or a set-top box, has no
 * `gps` provider. The two reads used to share one `listOfNotNull`, so that throw discarded the
 * other provider's answer as well and the whole row became "Unreadable", even when the network
 * provider had been read successfully and was enabled. That is the same "a block that throws costs
 * the rows it had already read" fault the other connectivity blocks were fixed for; this row is the
 * last one that could still lose a reading.
 *
 * A provider that was read decides the value. A provider that threw contributes nothing, and when
 * neither provider reports enabled the row only says "None enabled" if both reads actually
 * happened: with one unread the state of that provider is unknown, so the row reports the failure
 * instead of a measurement it never took. Every case the row could already render renders the same
 * text as before; only a reading that used to be thrown away is now reported.
 */
internal fun locationProviderRow(gps: Reading<Boolean>, network: Reading<Boolean>): ProviderRow {
    val enabled = listOfNotNull(
        "GPS".takeIf { gps.value == true },
        "Network".takeIf { network.value == true }
    )
    if (enabled.isNotEmpty()) return ProviderRow(enabled.joinToString(", "), null)
    val failure = gps.failure ?: network.failure
    return if (failure == null) ProviderRow("None enabled", null) else ProviderRow(null, unreadableNote(failure))
}
