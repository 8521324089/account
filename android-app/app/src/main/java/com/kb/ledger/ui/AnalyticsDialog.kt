package com.kb.ledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kb.ledger.data.dao.PersonBalanceEntity
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.Transaction
import com.kb.ledger.util.AnalyticsEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsDialog(
    persons: List<Person>,
    balances: List<PersonBalanceEntity>,
    transactions: List<Transaction>,
    onDismiss: () -> Unit,
    onSelectPerson: (Person) -> Unit
) {
    val personMap = remember(persons) { persons.associateBy { it.id } }
    val summary = remember(persons, balances, transactions) {
        AnalyticsEngine.computeAnalytics(persons, balances, transactions)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Analytics & Visual Reports", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Outstanding Totals
                item {
                    Text("Outstanding Balances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Total You Give", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC62828))
                                Text(
                                    "₹${summary.totalYouGiveCents / 100.0}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC62828)
                                )
                                Text("Money you owe", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Total You Get", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                                Text(
                                    "₹${summary.totalYouGetCents / 100.0}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                                Text("Money owed to you", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }

                // Volume & Cashflow
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Transaction Cashflow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total Given:")
                                Text("₹${summary.periodGiveCents / 100.0}", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total Received:")
                                Text("₹${summary.periodReceiveCents / 100.0}", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Net Cashflow:", fontWeight = FontWeight.Bold)
                                Text(
                                    "₹${summary.periodNetCashFlowCents / 100.0}",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (summary.periodNetCashFlowCents >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                        }
                    }
                }

                // Visual Monthly Breakdown (Canvas Chart)
                if (summary.trends.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Monthly Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Gave (Red) vs Received (Green)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Spacer(modifier = Modifier.height(16.dp))

                                val maxAmount = remember(summary.trends) {
                                    val max = summary.trends.maxOfOrNull { maxOf(it.giveCents, it.receiveCents) } ?: 100L
                                    if (max == 0L) 100L else max
                                }

                                Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                                    val barWidth = 24.dp.toPx()
                                    val groupSpacing = size.width / summary.trends.size
                                    val chartHeight = size.height - 24.dp.toPx()

                                    summary.trends.forEachIndexed { index, trend ->
                                        val groupCenter = index * groupSpacing + groupSpacing / 2

                                        // Give bar (Red)
                                        val giveBarHeight = (trend.giveCents.toFloat() / maxAmount) * chartHeight
                                        drawRoundRect(
                                            color = Color(0xFFE57373),
                                            topLeft = Offset(groupCenter - barWidth - 2.dp.toPx(), chartHeight - giveBarHeight),
                                            size = Size(barWidth, giveBarHeight),
                                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                        )

                                        // Receive bar (Green)
                                        val receiveBarHeight = (trend.receiveCents.toFloat() / maxAmount) * chartHeight
                                        drawRoundRect(
                                            color = Color(0xFF81C784),
                                            topLeft = Offset(groupCenter + 2.dp.toPx(), chartHeight - receiveBarHeight),
                                            size = Size(barWidth, receiveBarHeight),
                                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    summary.trends.forEach {
                                        Text(it.periodLabel, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }

                // Top Debtors (Customers who owe you money)
                if (summary.topDebtors.isNotEmpty()) {
                    item {
                        Text("Top Debtors (You will get from)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(summary.topDebtors) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { personMap[item.personId]?.let { onSelectPerson(it) } },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            ListItem(
                                headlineContent = { Text(item.personName, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(item.phone) },
                                trailingContent = {
                                    Text("₹${item.balanceCents / 100.0}", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                }
                            )
                        }
                    }
                }

                // Top Creditors (Customers you owe money to)
                if (summary.topCreditors.isNotEmpty()) {
                    item {
                        Text("Top Creditors (You owe to)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(summary.topCreditors) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { personMap[item.personId]?.let { onSelectPerson(it) } },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            ListItem(
                                headlineContent = { Text(item.personName, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(item.phone) },
                                trailingContent = {
                                    Text("₹${item.balanceCents / 100.0}", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
