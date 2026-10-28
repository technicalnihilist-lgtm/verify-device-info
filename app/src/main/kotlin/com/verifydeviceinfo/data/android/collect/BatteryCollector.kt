package com.verifydeviceinfo.data.android.collect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.verifydeviceinfo.core.model.BatteryFacts
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.core.model.SpecGroup
import com.verifydeviceinfo.core.model.Verification
import kotlin.math.roundToInt

/**
 * Battery facts. Health, temperature and voltage come from the sticky BATTERY_CHANGED
 * broadcast; capacity properties come from BatteryManager. Design capacity is read from the
 * platform PowerProfile through reflection and is clearly marked as best-effort.
 */
class BatteryCollector(private val context: Context) {

    fun collect(): Pair<BatteryFacts, List<SpecField>> {
        val fields = mutableListOf<SpecField>()
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val intent: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        }

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else null

        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val healthText = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = tempTenths?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        val pluggedCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val pluggedText = when (pluggedCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Not charging"
            else -> "Unknown ($pluggedCode)"
        }
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val statusText = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

        val chargeCounter = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }
        val currentNow = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val energyCounter = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            ?.takeIf { it > 0 }

        // Cycle count: public since Android 14 (API 34).
        val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it > 0 }
        } else null

        val designCapacity = readDesignCapacityMah()

        // Estimated full-charge capacity: only meaningful at (or near) full charge.
        val estimatedFullMah = if (percent != null && percent >= 99 && chargeCounter != null) {
            (chargeCounter / 1000.0).roundToInt()
        } else null
        val healthPercent = if (designCapacity != null && estimatedFullMah != null && designCapacity > 0) {
            (estimatedFullMah * 100.0 / designCapacity).roundToInt().coerceIn(1, 150)
        } else null

        fields += FieldBuilder.value(
            "battery.percent", "Charge level", percent?.let { "$it%" }, SpecGroup.BATTERY,
            "BATTERY_CHANGED broadcast"
        )
        fields += FieldBuilder.value(
            "battery.health", "Battery health", healthText, SpecGroup.BATTERY, "BatteryManager.EXTRA_HEALTH"
        )
        fields += FieldBuilder.value(
            "battery.temperature", "Battery temperature",
            temperature?.let { String.format(java.util.Locale.US, "%.1f °C", it) }, SpecGroup.BATTERY,
            "BatteryManager.EXTRA_TEMPERATURE"
        )
        fields += FieldBuilder.value(
            "battery.voltage", "Battery voltage", voltage?.let { "$it mV" }, SpecGroup.BATTERY,
            "BatteryManager.EXTRA_VOLTAGE"
        )
        fields += FieldBuilder.value(
            "battery.technology", "Battery chemistry", technology, SpecGroup.BATTERY,
            "BatteryManager.EXTRA_TECHNOLOGY"
        )
        fields += FieldBuilder.value(
            "battery.status", "Charging status", statusText, SpecGroup.BATTERY, "BatteryManager.EXTRA_STATUS"
        )
        fields += FieldBuilder.value(
            "battery.plugged", "Power source", pluggedText, SpecGroup.BATTERY, "BatteryManager.EXTRA_PLUGGED"
        )
        fields += FieldBuilder.value(
            "battery.designCapacity", "Design capacity",
            designCapacity?.let { "$it mAh" }, SpecGroup.BATTERY,
            "com.android.internal.os.PowerProfile (reflection)", Confidence.LOW,
            Verification.INFERRED,
            note = if (designCapacity == null) {
                "The platform does not expose design capacity on this device."
            } else "Read from the platform power profile; not an official published figure."
        )
        fields += FieldBuilder.value(
            "battery.chargeCounter", "Charge counter",
            chargeCounter?.let { String.format(java.util.Locale.US, "%.1f mAh", it / 1000.0) },
            SpecGroup.BATTERY, "BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER", Confidence.MEDIUM
        )
        fields += FieldBuilder.value(
            "battery.currentNow", "Instant current",
            currentNow?.let { String.format(java.util.Locale.US, "%.0f mA", it / 1000.0) },
            SpecGroup.BATTERY, "BatteryManager.BATTERY_PROPERTY_CURRENT_NOW", Confidence.LOW,
            note = "Sign convention varies by vendor."
        )
        fields += FieldBuilder.value(
            "battery.energyCounter", "Energy counter",
            energyCounter?.let { String.format(java.util.Locale.US, "%.0f mWh", it / 1000.0) },
            SpecGroup.BATTERY, "BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER", Confidence.LOW
        )
        fields += if (cycleCount != null) {
            FieldBuilder.value(
                "battery.cycleCount", "Charge cycles", cycleCount.toString(), SpecGroup.BATTERY,
                "BatteryManager.EXTRA_CYCLE_COUNT"
            )
        } else {
            FieldBuilder.unsupported(
                "battery.cycleCount", "Charge cycles", SpecGroup.BATTERY, "BatteryManager.EXTRA_CYCLE_COUNT",
                if (Build.VERSION.SDK_INT >= 34) {
                    "This device's battery service did not report a cycle count."
                } else {
                    "Charge cycle count is only exposed from Android 14 (API 34). This device runs " +
                        "API ${Build.VERSION.SDK_INT}."
                }
            )
        }
        fields += FieldBuilder.value(
            "battery.healthPercent", "Estimated capacity vs design",
            healthPercent?.let { "$it%" }, SpecGroup.BATTERY, "Derived from charge counter and design capacity",
            Confidence.LOW, Verification.INFERRED,
            note = if (healthPercent == null) {
                "Requires the device to be at full charge with a readable charge counter and design capacity."
            } else "Approximate. Charging to 100% and letting the counter settle improves accuracy."
        )

        val facts = BatteryFacts(
            percent = percent,
            health = healthText,
            healthCode = healthCode,
            temperatureCelsius = temperature,
            voltageMillivolts = voltage,
            technology = technology,
            plugged = pluggedText,
            status = statusText,
            designCapacityMah = designCapacity,
            chargeCounterMicroAmpHours = chargeCounter,
            currentNowMicroAmps = currentNow,
            cycleCount = cycleCount,
            powerProfileDesignCapacityMah = designCapacity,
            healthPercent = healthPercent
        )
        return facts to fields
    }

    private fun readDesignCapacityMah(): Int? = try {
        val clazz = Class.forName("com.android.internal.os.PowerProfile")
        val instance = clazz.getConstructor(Context::class.java).newInstance(context)
        val method = clazz.getMethod("getBatteryCapacity")
        val value = method.invoke(instance) as? Double
        value?.takeIf { it > 0 }?.roundToInt()
    } catch (ignored: Throwable) {
        null
    }
}
