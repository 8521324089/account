package com.kb.ledger.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.util.StatementExportHelper
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class StatementDateFilter {
    ALL_TIME,
    THIS_MONTH,
    LAST_30_DAYS,
    CUSTOM
}

@Composable
fun ExportStatementDialog(
    person: Person,
    transactions: List<Transaction>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var dateFilter by remember { mutableStateOf(StatementDateFilter.ALL_TIME) }
    var customStartDate by remember {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -30) }
        mutableStateOf(cal.timeInMillis)
    }
    var customEndDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var isExporting by remember { mutableStateOf(false) }

    val (startDate, endDate) = remember(dateFilter, customStartDate, customEndDate) {
        val now = System.currentTimeMillis()
        when (dateFilter) {
            StatementDateFilter.ALL_TIME -> Pair(null, null)
            StatementDateFilter.LAST_30_DAYS -> Pair(now - 30L * 24 * 60 * 60 * 1000, now)
            StatementDateFilter.THIS_MONTH -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                Pair(cal.timeInMillis, now)
            }
            StatementDateFilter.CUSTOM -> Pair(customStartDate, customEndDate)
        }
    }

    val isDateRangeInvalid = startDate != null && endDate != null && startDate > endDate

    val summary = remember(person, transactions, startDate, endDate) {
        StatementExportHelper.computeSummary(person, transactions, startDate, endDate)
    }

    val csvContent = remember(person, transactions, startDate, endDate) {
        StatementExportHelper.generateCsv(person, transactions, startDate, endDate)
    }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            isExporting = true
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os).use { writer ->
                        writer.write(csvContent)
                    }
                }
                Toast.makeText(context, "Statement saved to device successfully!", Toast.LENGTH_SHORT).show()
                onDismiss()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export statement: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isExporting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        "Export Statement",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "CSV format (.csv) for Excel & Sheets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Customer details card (dedicated phone row, marquee for name)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = person.name,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(iterations = Int.MAX_VALUE)
                                    .semantics { contentDescription = person.name }
                            )
                            val phoneText = person.phone.trim().ifBlank { "No phone number" }
                            Text(
                                text = phoneText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Date filter selector
                Text(
                    "Select Period",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = dateFilter == StatementDateFilter.ALL_TIME,
                        onClick = { dateFilter = StatementDateFilter.ALL_TIME },
                        label = { Text("All Time", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = dateFilter == StatementDateFilter.THIS_MONTH,
                        onClick = { dateFilter = StatementDateFilter.THIS_MONTH },
                        label = { Text("This Month", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = dateFilter == StatementDateFilter.LAST_30_DAYS,
                        onClick = { dateFilter = StatementDateFilter.LAST_30_DAYS },
                        label = { Text("30 Days", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = dateFilter == StatementDateFilter.CUSTOM,
                        onClick = { dateFilter = StatementDateFilter.CUSTOM },
                        label = { Text("Custom", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                if (dateFilter == StatementDateFilter.CUSTOM) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Start Date Picker
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val cal = Calendar.getInstance().apply { timeInMillis = customStartDate }
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val c = Calendar.getInstance().apply {
                                                set(y, m, d, 0, 0, 0)
                                            }
                                            customStartDate = c.timeInMillis
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("From Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(dateFormat.format(Date(customStartDate)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        // End Date Picker
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val cal = Calendar.getInstance().apply { timeInMillis = customEndDate }
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val c = Calendar.getInstance().apply {
                                                set(y, m, d, 23, 59, 59)
                                            }
                                            customEndDate = c.timeInMillis
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("To Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(dateFormat.format(Date(customEndDate)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    if (isDateRangeInvalid) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Start date cannot be after end date.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Summary card (themed, high contrast, no hardcoded white)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "Statement Overview",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Opening Balance:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${summary.openingBalanceCents / 100.0}", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Given in Period:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${summary.totalGiveCents / 100.0}", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Received in Period:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${summary.totalReceiveCents / 100.0}", color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Closing Balance:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            val absClose = kotlin.math.abs(summary.closingBalanceCents) / 100.0
                            val label = when {
                                summary.closingBalanceCents > 0 -> "₹$absClose (You Get)"
                                summary.closingBalanceCents < 0 -> "₹$absClose (You Give)"
                                else -> "₹0.00 (Settled)"
                            }
                            val closeColor = when {
                                summary.closingBalanceCents > 0 -> Color(0xFF22C55E)
                                summary.closingBalanceCents < 0 -> Color(0xFFEF4444)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                label,
                                fontWeight = FontWeight.Bold,
                                color = closeColor
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Entries in period: ${summary.transactionCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Ledger Statement - ${person.name}")
                            putExtra(Intent.EXTRA_TEXT, csvContent)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Statement via"))
                    },
                    enabled = !isExporting && !isDateRangeInvalid,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
                Button(
                    onClick = {
                        val sanitized = person.name.trim().replace(Regex("[^a-zA-Z0-9]"), "_")
                        val fileName = if (sanitized.isNotBlank()) "${sanitized}_statement.csv" else "statement.csv"
                        saveFileLauncher.launch(fileName)
                    },
                    enabled = !isExporting && !isDateRangeInvalid,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Exporting...")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save CSV")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isExporting
            ) {
                Text("Cancel")
            }
        }
    )
}
