package com.verifydeviceinfo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verifydeviceinfo.core.model.ScoreBand
import com.verifydeviceinfo.ui.AppViewModel
import com.verifydeviceinfo.ui.Screen
import com.verifydeviceinfo.ui.UiState
import com.verifydeviceinfo.ui.components.DeviceIllustration
import com.verifydeviceinfo.ui.components.MockDataBanner
import com.verifydeviceinfo.ui.components.ScanProgressBar
import com.verifydeviceinfo.ui.components.ScoreRing
import com.verifydeviceinfo.ui.components.SectionCard
import com.verifydeviceinfo.ui.theme.DiagnosticColors
import com.verifydeviceinfo.ui.theme.ScreenPadding
import com.verifydeviceinfo.ui.theme.bandColor

@Composable
fun DashboardScreen(
    state: UiState,
    viewModel: AppViewModel,
    navigate: (Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Verify Device Info",
            style = MaterialTheme.typography.displaySmall
        )
        Text(
            "Honest hardware inspection — every result shows its evidence.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        if (state.snapshot?.isMock == true) {
            MockDataBanner()
            Spacer(Modifier.height(12.dp))
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DeviceIllustration(
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    scanning = state.scanning
                )
                Spacer(Modifier.height(8.dp))

                if (state.scanning) {
                    val progress = state.progress
                    ScanProgressBar(
                        fraction = progress?.fraction ?: 0f,
                        stageLabel = progress?.stageLabel ?: "Starting"
                    )
                } else {
                    val snapshot = state.snapshot
                    Text(
                        if (snapshot == null) "No scan yet"
                        else "${snapshot.identity.manufacturer} ${snapshot.identity.model}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (snapshot == null) {
                            "Run a scan to collect this device's facts."
                        } else {
                            "Android ${snapshot.software.androidRelease} · API ${snapshot.software.apiLevel} · " +
                                "${snapshot.fields.size} facts collected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.scan() },
                    enabled = !state.scanning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.snapshot == null) "Run device scan" else "Run a new scan")
                }
                if (state.snapshot == null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { navigate(Screen.Diagnostics) }) {
                        Text("Or run diagnostics first")
                    }
                }
            }
        }

        state.report?.let { report ->
            Spacer(Modifier.height(16.dp))
            SectionCard(
                title = "Health overview",
                subtitle = "Scores are computed only from evidence that was actually available. " +
                    "A missing value lowers coverage, never the score."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val hardware = report.scores.byName("Hardware health")
                    val security = report.scores.byName("Software and security")
                    val battery = report.scores.byName("Battery condition")
                    ScoreRing(
                        score = hardware?.score,
                        band = hardware?.band ?: ScoreBand.UNKNOWN,
                        label = "Hardware",
                        size = 92
                    )
                    ScoreRing(
                        score = security?.score,
                        band = security?.band ?: ScoreBand.UNKNOWN,
                        label = "Security",
                        size = 92
                    )
                    ScoreRing(
                        score = battery?.score,
                        band = battery?.band ?: ScoreBand.UNKNOWN,
                        label = "Battery",
                        size = 92
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(bandColor(report.authenticity.riskLevel.let {
                            when (it) {
                                com.verifydeviceinfo.core.model.RiskLevel.LOW -> ScoreBand.GOOD
                                com.verifydeviceinfo.core.model.RiskLevel.MODERATE,
                                com.verifydeviceinfo.core.model.RiskLevel.ELEVATED -> ScoreBand.FAIR
                                com.verifydeviceinfo.core.model.RiskLevel.HIGH -> ScoreBand.POOR
                                com.verifydeviceinfo.core.model.RiskLevel.UNKNOWN -> ScoreBand.UNKNOWN
                            }
                        }).copy(alpha = 0.12f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        report.authenticity.headline,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Explore", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val tiles = listOf(
            Triple("Device overview", "Identity and headline facts", Icons.Filled.Info) to Screen.Overview,
            Triple("All specifications", "Searchable A-to-Z list", Icons.Filled.Search) to Screen.Specs,
            Triple("Hardware explorer", "Components and capabilities", Icons.Filled.List) to Screen.Hardware,
            Triple("Authenticity", "Reference comparison and risk", Icons.Filled.Star) to Screen.Authenticity,
            Triple("Parts and repairs", "Five-state component analysis", Icons.Filled.Build) to Screen.Parts,
            Triple("Battery", "Health, temperature, charging", Icons.Filled.Warning) to Screen.Battery,
            Triple("Security", "Root, boot and patch state", Icons.Filled.Lock) to Screen.Security,
            Triple("Device age", "Launch, firmware and usage age", Icons.Filled.DateRange) to Screen.Age,
            Triple("Diagnostics", "Interactive hardware tests", Icons.Filled.PlayArrow) to Screen.Diagnostics,
            Triple("Report and export", "PDF, JSON and sharing", Icons.Filled.Share) to Screen.Report,
            Triple("History", "Previous scans on this device", Icons.Filled.DateRange) to Screen.History
        )

        tiles.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (content, screen) ->
                    NavTile(
                        title = content.first,
                        subtitle = content.second,
                        icon = content.third,
                        modifier = Modifier.weight(1f),
                        onClick = { navigate(screen) }
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "This app reports what the device discloses about itself. It cannot prove that physical " +
                "components are genuine, and it never presents missing data as a fault.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NavTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = DiagnosticColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
