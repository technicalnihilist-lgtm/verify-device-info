package com.verifydeviceinfo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verifydeviceinfo.core.model.Confidence
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.ScoreBand
import com.verifydeviceinfo.core.model.SpecField
import com.verifydeviceinfo.ui.theme.DiagnosticColors
import com.verifydeviceinfo.ui.theme.bandColor
import com.verifydeviceinfo.ui.theme.statusColor
import com.verifydeviceinfo.ui.theme.statusLabel
import kotlin.math.roundToInt

@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun StatusChip(status: DiagnosticStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ConfidenceText(confidence: Confidence, modifier: Modifier = Modifier) {
    val label = when (confidence) {
        Confidence.HIGH -> "high confidence"
        Confidence.MEDIUM -> "medium confidence"
        Confidence.LOW -> "low confidence"
        Confidence.NONE -> "no confidence"
    }
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun ScoreRing(
    score: Int?,
    band: ScoreBand,
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 96
) {
    val animated by animateFloatAsState(
        targetValue = (score ?: 0) / 100f,
        animationSpec = tween(durationMillis = 900),
        label = "score"
    )
    val color = bandColor(band)
    Box(modifier = modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.dp)) {
            val stroke = size.dp.toPx() * 0.09f
            drawArc(
                color = color.copy(alpha = 0.15f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (score == null) "—" else score.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = color
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SpecRow(field: SpecField, modifier: Modifier = Modifier, showSource: Boolean = true) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                field.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            StatusChip(field.status)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            field.value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        if (showSource) {
            Text(
                buildString {
                    append(field.source)
                    append(" · ")
                    append(
                        when (field.verification) {
                            com.verifydeviceinfo.core.model.Verification.DIRECTLY_VERIFIED -> "directly read"
                            com.verifydeviceinfo.core.model.Verification.INFERRED -> "inferred"
                            com.verifydeviceinfo.core.model.Verification.INACCESSIBLE -> "not accessible"
                        }
                    )
                    append(" · ")
                    append(
                        when (field.confidence) {
                            Confidence.HIGH -> "high"
                            Confidence.MEDIUM -> "medium"
                            Confidence.LOW -> "low"
                            Confidence.NONE -> "none"
                        }
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        field.note?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
fun ScanProgressBar(fraction: Float, stageLabel: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = DiagnosticColors.Accent,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stageLabel, style = MaterialTheme.typography.bodySmall)
            Text(
                "${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MockDataBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DiagnosticColors.Warning.copy(alpha = 0.15f))
            .border(1.dp, DiagnosticColors.Warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            "MOCK DATA — development fixture. These results are not from a real device and are " +
                "never mixed with real scans.",
            style = MaterialTheme.typography.bodySmall,
            color = DiagnosticColors.Warning
        )
    }
}

/**
 * Abstract device illustration drawn with Canvas — no vendor artwork is copied, so nothing here
 * implies a specific manufacturer's product photography.
 */
@Composable
fun DeviceIllustration(
    modifier: Modifier = Modifier,
    scanning: Boolean = false,
    accent: Color = DiagnosticColors.Accent
) {
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bodyWidth = w * 0.52f
        val bodyHeight = h * 0.86f
        val left = (w - bodyWidth) / 2f
        val top = (h - bodyHeight) / 2f
        val radius = bodyWidth * 0.13f

        // glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w / 2f, h / 2f),
                radius = w * 0.55f
            ),
            radius = w * 0.55f,
            center = Offset(w / 2f, h / 2f)
        )

        // body
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = 0.16f),
                    accent.copy(alpha = 0.04f)
                )
            ),
            topLeft = Offset(left, top),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.55f),
            topLeft = Offset(left, top),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            style = Stroke(width = 2.2f)
        )

        // screen
        val inset = bodyWidth * 0.075f
        drawRoundRect(
            color = accent.copy(alpha = 0.10f),
            topLeft = Offset(left + inset, top + inset * 1.6f),
            size = Size(bodyWidth - inset * 2, bodyHeight - inset * 3.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.55f, radius * 0.55f)
        )

        // camera island
        drawCircle(
            color = accent.copy(alpha = 0.5f),
            radius = bodyWidth * 0.035f,
            center = Offset(w / 2f, top + bodyHeight * 0.055f)
        )

        // internal component grid inside the screen
        val gridTop = top + bodyHeight * 0.16f
        val gridBottom = top + bodyHeight * 0.82f
        val rows = 7
        val rowHeight = (gridBottom - gridTop) / rows
        repeat(rows) { row ->
            val y = gridTop + rowHeight * row + rowHeight / 2
            val lineWidth = (bodyWidth - inset * 3) * (0.45f + ((row * 37) % 50) / 100f)
            drawLine(
                color = accent.copy(alpha = 0.20f),
                start = Offset(left + inset * 1.5f, y),
                end = Offset(left + inset * 1.5f + lineWidth, y),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }

        // scanning sweep line
        if (scanning) {
            val sweepY = gridTop + (gridBottom - gridTop) * sweep
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent),
                    startY = sweepY - bodyHeight * 0.09f,
                    endY = sweepY + bodyHeight * 0.09f
                ),
                topLeft = Offset(left + inset, sweepY - bodyHeight * 0.09f),
                size = Size(bodyWidth - inset * 2, bodyHeight * 0.18f)
            )
        }
    }
}

@Composable
fun MonospaceBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
