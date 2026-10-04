package com.pacemckinney.tally.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.engine.Categories
import com.pacemckinney.tally.engine.Insight
import com.pacemckinney.tally.engine.InsightEngine
import com.pacemckinney.tally.engine.Kind
import com.pacemckinney.tally.engine.Severity
import com.pacemckinney.tally.engine.Txn

fun money(v: Double) = InsightEngine.money(v)

fun categoryIcon(c: String): ImageVector = when (c) {
    Categories.FOOD_AND_DRINK -> Icons.Outlined.Restaurant
    Categories.GENERAL_MERCHANDISE -> Icons.Outlined.ShoppingBag
    Categories.TRANSPORTATION -> Icons.Outlined.DirectionsCar
    Categories.RENT_AND_UTILITIES -> Icons.Outlined.Home
    Categories.LOAN_PAYMENTS -> Icons.Outlined.AccountBalance
    Categories.ENTERTAINMENT -> Icons.Outlined.Movie
    Categories.GENERAL_SERVICES -> Icons.Outlined.ReceiptLong
    Categories.PERSONAL_CARE -> Icons.Outlined.Spa
    Categories.MEDICAL -> Icons.Outlined.LocalHospital
    Categories.HOME_IMPROVEMENT -> Icons.Outlined.Handyman
    Categories.TRAVEL -> Icons.Outlined.Flight
    Categories.GOVERNMENT_AND_NON_PROFIT -> Icons.Outlined.VolunteerActivism
    Categories.BANK_FEES -> Icons.Outlined.Payments
    Categories.TRANSFER_OUT -> Icons.Outlined.CallMade
    Categories.TRANSFER_IN -> Icons.Outlined.CallReceived
    Categories.INCOME -> Icons.Outlined.Work
    Categories.EXCLUDED -> Icons.Outlined.Block
    Categories.SAVINGS -> Icons.Outlined.Savings
    Categories.INTERNAL -> Icons.Outlined.SwapHoriz
    else -> Icons.Outlined.MoreHoriz
}

@Composable
fun Section(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    action?.invoke()
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LocalTones.current.muted, modifier = modifier)
}

@Composable
fun CategoryBadge(category: String, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(category), contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
fun InsightRow(i: Insight, onClick: (() -> Unit)? = null) {
    val tone = LocalTones.current.of(i.severity)
    val icon = when (i.severity) {
        Severity.ALERT -> Icons.Outlined.ErrorOutline
        Severity.WARN -> Icons.Outlined.WarningAmber
        Severity.GOOD -> Icons.Outlined.CheckCircle
        Severity.INFO -> Icons.Outlined.Info
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(i.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(i.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Colour for each kind of money, used in rows, bars and charts. */
@Composable
fun kindColor(k: Kind?): Color {
    val tones = LocalTones.current
    return when (k) {
        Kind.INCOME -> tones.income
        Kind.SAVINGS -> tones.savings
        Kind.LOAN_PAYMENT -> tones.loan
        Kind.INTERNAL, Kind.EXCLUDED, Kind.HIDDEN -> tones.muted
        else -> MaterialTheme.colorScheme.secondary
    }
}

fun kindIcon(k: Kind?, category: String): ImageVector = when (k) {
    Kind.SAVINGS -> Icons.Outlined.Savings
    Kind.LOAN_PAYMENT -> Icons.Outlined.AccountBalance
    Kind.INTERNAL -> Icons.Outlined.SwapHoriz
    else -> categoryIcon(category)
}

@Composable
fun TxnRow(t: Txn, kind: Kind?, note: String?, accountLabel: String?, onClick: () -> Unit) {
    val tones = LocalTones.current
    val inflow = t.amount < 0
    val dimmed = kind == Kind.INTERNAL || kind == Kind.EXCLUDED
    val tint = kindColor(kind)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(kindIcon(kind, t.effectiveCategory), null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                note ?: Categories.label(t.effectiveCategory),
                accountLabel,
                if (t.userCategory != null) "edited" else null,
            ).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = tones.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (inflow) "+" else "−") + money(kotlin.math.abs(t.amount)),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                fontWeight = FontWeight.Medium,
                color = when {
                    dimmed -> tones.muted
                    kind == Kind.SAVINGS || kind == Kind.LOAN_PAYMENT -> tint
                    inflow -> tones.income
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (t.pending) Text("Pending", style = MaterialTheme.typography.labelSmall, color = tones.warn)
        }
    }
}

/**
 * One bar split into coloured segments (e.g. where income went). Values are drawn left to right
 * as fractions of [total]; anything past the total is clipped.
 */
@Composable
fun SegmentBar(segments: List<Pair<Double, Color>>, total: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        drawRect(track)
        if (total <= 0) return@Canvas
        var x = 0f
        for ((v, color) in segments) {
            if (v <= 0) continue
            val w = (v / total * size.width).toFloat().coerceAtMost(size.width - x)
            if (w <= 0) break
            drawRect(color, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
            x += w + 2f
        }
    }
}

/** Horizontal bar used for category totals and budgets. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/**
 * Cumulative spending this month (solid) against last month (dashed). Where the solid line sits
 * above the dashed one, you're spending faster than last month.
 */
@Composable
fun PaceChart(thisMonth: List<Double>, lastMonth: List<Double>, daysInMonth: Int, modifier: Modifier = Modifier) {
    val tones = LocalTones.current
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val maxV = maxOf(thisMonth.maxOrNull() ?: 0.0, lastMonth.maxOrNull() ?: 0.0, 1.0)
        val days = maxOf(daysInMonth, lastMonth.size, 2)
        fun x(i: Int) = size.width * i / (days - 1).toFloat()
        fun y(v: Double) = size.height - (v / maxV * size.height * 0.92).toFloat()
        for (g in 1..3) {
            val gy = size.height * g / 4f
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        fun path(vals: List<Double>) = Path().apply {
            vals.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
        }
        if (lastMonth.size > 1) drawPath(path(lastMonth), tones.chartPrev,
            style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)), cap = StrokeCap.Round))
        if (thisMonth.size > 1) drawPath(path(thisMonth), accent, style = Stroke(width = 6f, cap = StrokeCap.Round))
        thisMonth.lastOrNull()?.let { drawCircle(accent, 9f, Offset(x(thisMonth.size - 1), y(it))) }
    }
}

@Composable
fun LegendDot(color: Color, text: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 5f,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null, cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = LocalTones.current.muted)
    }
}

@Composable
fun CategoryPickerDialog(txn: Txn, onDismiss: () -> Unit, onPick: (String?, Boolean) -> Unit) {
    var selected by remember { mutableStateOf(txn.effectiveCategory) }
    var all by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(txn.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("${money(kotlin.math.abs(txn.amount))} on ${txn.date}" + if (txn.name != txn.displayName) "\n${txn.name}" else "",
                    style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(Categories.labels.keys.toList()) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = c }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == c, onClick = { selected = c })
                            Icon(categoryIcon(c), null, Modifier.size(18.dp), tint = LocalTones.current.muted)
                            Spacer(Modifier.width(8.dp))
                            Text(Categories.label(c) + when (c) {
                                Categories.EXCLUDED -> " (ignore in totals)"
                                Categories.SAVINGS -> " (money set aside)"
                                Categories.INTERNAL -> " (ignore)"
                                else -> ""
                            })
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { all = !all }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all, onCheckedChange = { all = it })
                    Text("Apply to every \"${com.pacemckinney.tally.engine.Recurring.key(txn)}\" transaction",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected, all) }) { Text("Save") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (txn.userCategory != null) TextButton(onClick = { onPick(null, false) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
