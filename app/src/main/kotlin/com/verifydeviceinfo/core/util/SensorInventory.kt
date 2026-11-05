package com.verifydeviceinfo.core.util

import com.verifydeviceinfo.core.model.SensorFacts

/**
 * Number of logical sensors in a `SensorManager.getSensorList(TYPE_ALL)` enumeration.
 *
 * A wake-up sensor is not a second sensor. It is the same physical device published by the HAL
 * under a second handle so that it can batch and deliver while the application processor is
 * suspended; both handles report the same sensor type. Most devices therefore return two entries
 * for every sensor that supports wake-up delivery, and the raw list length overstates the hardware
 * (a phone with 22 sensors enumerates around 40).
 *
 * A type that publishes at least one ordinary (non wake-up) handle is counted by those handles
 * alone, so a second physical sensor of the same type is still counted. A wake-up handle is counted
 * only for a type that publishes nothing else, because some sensors — significant motion, for
 * example — are wake-up only and would otherwise disappear from the count.
 *
 * The list is a few dozen entries at most, so the nested scan is cheaper than the set it replaces.
 */
fun logicalSensorCount(sensors: List<SensorFacts>): Int = sensors.count { sensor ->
    !sensor.wakeUpSensor || sensors.none { !it.wakeUpSensor && it.type == sensor.type }
}
