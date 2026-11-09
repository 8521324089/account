package com.kb.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.RecurrenceFrequency
import com.kb.ledger.data.model.RecurringRule
import com.kb.ledger.data.model.TransactionType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringRulesDialog(
    rules: List<RecurringRule>,
    persons: List<Person>,
    onDismiss: () -> Unit,
    onAddRule: (RecurringRule) -> Unit,
    onUpdateRule: (RecurringRule) -> Unit,
    onDeleteRule: (RecurringRule) -> Unit
) {
    val personMap = remember(persons) { persons.associateBy { it.id } }
    var showCreateDialog by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<RecurringRule?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    if (ruleToDelete != null) {
        AlertDialog(
            onDismissRequest = { ruleToDelete = null },
            title = { Text("Delete Recurring Template?") },
            text = { Text("This will prevent future automated transactions from being created. Past recorded transactions will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    ruleToDelete?.let { onDeleteRule(it) }
                    ruleToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDelete = null }) { Text("Cancel") }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Recurring Transactions", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Rule")
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Recurring Entry") }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp)
            ) {
                if (rules.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No recurring schedules yet", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Automate rent, daily milk, or recurring dues effortlessly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rules, key = { it.id }) { rule ->
                            val customer = personMap[rule.personId]
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            customer?.name ?: "Customer",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${rule.frequency.name} • Next: ${dateFormat.format(Date(rule.nextExecutionDate))}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                        if (!rule.note.isNullOrBlank()) {
                                            Text(rule.note, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (rule.isPaused) {
                                            Text("PAUSED", color = Color(0xFFC62828), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "₹${rule.amountCents / 100.0}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (rule.type == TransactionType.GIVE) Color(0xFFC62828) else Color(0xFF2E7D32)
                                        )
                                        Text(
                                            if (rule.type == TransactionType.GIVE) "You Give" else "You Get",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row {
                                            IconButton(
                                                onClick = { onUpdateRule(rule.copy(isPaused = !rule.isPaused)) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    if (rule.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                    contentDescription = if (rule.isPaused) "Resume" else "Pause",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            IconButton(
                                                onClick = { ruleToDelete = rule },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreateRecurringRuleDialog(
                persons = persons,
                onDismiss = { showCreateDialog = false },
                onAdd = { newRule ->
                    onAddRule(newRule)
                    showCreateDialog = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRecurringRuleDialog(
    persons: List<Person>,
    onDismiss: () -> Unit,
    onAdd: (RecurringRule) -> Unit
) {
    val context = LocalContext.current
    var selectedPerson by remember { mutableStateOf(persons.firstOrNull()) }
    var expandedCustomerDropdown by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.RECEIVE) }
    var frequency by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
    var note by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Recurring Entry") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Customer selection
                Text("Select Customer", style = MaterialTheme.typography.labelSmall)
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { expandedCustomerDropdown = true },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        selectedPerson?.name ?: "No customers available",
                        modifier = Modifier.padding(12.dp)
                    )
                }
                DropdownMenu(
                    expanded = expandedCustomerDropdown,
                    onDismissRequest = { expandedCustomerDropdown = false }
                ) {
                    persons.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name} (${p.phone})") },
                            onClick = {
                                selectedPerson = p
                                expandedCustomerDropdown = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FilterChip(
                        selected = type == TransactionType.GIVE,
                        onClick = { type = TransactionType.GIVE },
                        label = { Text("YOU GAVE") }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    FilterChip(
                        selected = type == TransactionType.RECEIVE,
                        onClick = { type = TransactionType.RECEIVE },
                        label = { Text("YOU GOT") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Frequency chips
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    RecurrenceFrequency.values().forEach { freq ->
                        FilterChip(
                            selected = frequency == freq,
                            onClick = { frequency = freq },
                            label = { Text(freq.name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Description (e.g. Rent, Milk, Wifi)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedAmount = amountText.toDoubleOrNull()
                val person = selectedPerson
                if (parsedAmount != null && parsedAmount > 0 && person != null) {
                    val amountCents = (parsedAmount * 100).toLong()
                    val rule = RecurringRule(
                        personId = person.id,
                        amountCents = amountCents,
                        type = type,
                        note = note.ifBlank { null },
                        frequency = frequency,
                        startDate = startDate,
                        nextExecutionDate = startDate
                    )
                    onAdd(rule)
                }
            }) {
                Text("Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
