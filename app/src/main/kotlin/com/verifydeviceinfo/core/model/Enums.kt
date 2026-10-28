package com.verifydeviceinfo.core.model

/** Which operating system the snapshot was actually collected from. Never guessed. */
enum class Platform { ANDROID, IOS, UNKNOWN }

/**
 * The only statuses a diagnostic result may carry. UNVERIFIED and NOT_SUPPORTED are
 * first-class outcomes, not failures: absence of evidence is never treated as evidence.
 */
enum class DiagnosticStatus {
    VERIFIED,
    PASS,
    WARNING,
    FAILED,
    UNVERIFIED,
    NOT_SUPPORTED,
    PERMISSION_REQUIRED;

    val isConcerning: Boolean get() = this == FAILED || this == WARNING
    val isPositive: Boolean get() = this == VERIFIED || this == PASS
}

/** How much weight a value or finding deserves. */
enum class Confidence { HIGH, MEDIUM, LOW, NONE }

/** Where a value physically came from, so a reader can audit it. */
enum class EvidenceSource {
    BUILD_FIELDS,
    SYSTEM_PROPERTY,
    ANDROID_API,
    PROC_FILESYSTEM,
    PACKAGE_MANAGER,
    SENSOR_MANAGER,
    CAMERA_MANAGER,
    MANUFACTURER_REFERENCE,
    OFFICIAL_DOCUMENTATION,
    USER_INPUT,
    DERIVED,
    HEURISTIC
}

/** Whether a value was read, computed from other values, or simply unavailable. */
enum class Verification { DIRECTLY_VERIFIED, INFERRED, INACCESSIBLE }

enum class SpecGroup {
    IDENTITY, SOFTWARE, PROCESSOR, GRAPHICS, MEMORY, STORAGE, DISPLAY,
    BATTERY, CAMERA, SENSOR, CONNECTIVITY, AUDIO, BIOMETRIC, SECURITY,
    SOFTWARE_INVENTORY, THERMAL, UPTIME, OTHER
}

enum class RiskLevel { LOW, MODERATE, ELEVATED, HIGH, UNKNOWN }

/**
 * The five-state component classification required by the parts module. These states are
 * deliberately distinct: "not inspected" is never rendered as "counterfeit".
 */
enum class ComponentState {
    ORIGINALITY_VERIFIED,
    POSSIBLE_REPLACEMENT,
    POSSIBLE_COUNTERFEIT,
    NO_ANOMALY_UNVERIFIED,
    UNABLE_TO_INSPECT
}

enum class ScoreBand { GOOD, FAIR, POOR, UNKNOWN }

/** Runtime permissions the diagnostics can ask for. */
enum class AppPermission { CAMERA, MICROPHONE, LOCATION, BLUETOOTH, NEARBY_WIFI }

enum class PermissionState { GRANTED, DENIED, PERMANENTLY_DENIED, NOT_REQUIRED, NOT_APPLICABLE }
