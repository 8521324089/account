package com.kb.ledger.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kb.ledger.data.model.Person
import com.kb.ledger.util.PaymentReminderHelper
import com.kb.ledger.util.PaymentReminderScheduler

@Composable
fun PaymentReminderDialog(
    person: Person,
    balanceCents: Long,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var messageText by remember(person, balanceCents) {
        mutableStateOf(PaymentReminderHelper.buildReminderMessage(person.name, balanceCents))
    }

    val absBalance = kotlin.math.abs(balanceCents) / 100.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (balanceCents > 0) "Payment Reminder" else "Account Statement Message",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Customer & Balance Info Card
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
                                person.name,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val phoneDisplay = person.phone.trim().ifBlank { "No phone number provided" }
                            Text(
                                phoneDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val (balanceLabel, balanceColor) = when {
                                balanceCents > 0 -> "You Get: ₹$absBalance" to Color(0xFF22C55E)
                                balanceCents < 0 -> "You Give: ₹$absBalance" to Color(0xFFEF4444)
                                else -> "Settled: ₹0.00" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                balanceLabel,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = balanceColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Editable Draft Field
                Text(
                    "Message Draft (Editable):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Opens a draft in the selected app. No message will be sent without your explicit review.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    "Send Message Draft Via:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // WhatsApp
                    Button(
                        onClick = {
                            val normalized = PaymentReminderHelper.normalizeWhatsAppPhone(person.phone)
                            if (normalized == null) {
                                Toast.makeText(
                                    context,
                                    "Please provide a valid phone number for WhatsApp.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@Button
                            }
                            try {
                                val intent = PaymentReminderHelper.createWhatsAppIntent(person.phone, messageText)
                                context.startActivity(intent)
                                onDismiss()
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    "WhatsApp is not installed. You can share via SMS or other apps.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Could not open WhatsApp: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF25D366),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("WhatsApp", fontWeight = FontWeight.SemiBold)
                    }

                    // SMS
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = PaymentReminderHelper.createSmsIntent(person.phone, messageText)
                                context.startActivity(intent)
                                onDismiss()
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    "SMS application not found. Use Share instead.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Could not open SMS: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Message,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SMS", fontWeight = FontWeight.SemiBold)
                    }

                    // Share Chooser
                    OutlinedButton(
                        onClick = {
                            try {
                                val chooser = PaymentReminderHelper.createShareChooserIntent(
                                    context = context,
                                    message = messageText,
                                    title = "Send Statement via"
                                )
                                context.startActivity(chooser)
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open share menu", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", fontWeight = FontWeight.SemiBold)
                    }
                }

                // In-App Local Reminder Scheduling (Only when customer owes money)
                if (balanceCents > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Schedule Local Reminder Alert:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                PaymentReminderScheduler.scheduleReminder(context, person.id, 3)
                                Toast.makeText(context, "Notification scheduled in 3 days", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("In 3 Days")
                        }
                        OutlinedButton(
                            onClick = {
                                PaymentReminderScheduler.scheduleReminder(context, person.id, 7)
                                Toast.makeText(context, "Notification scheduled in 7 days", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("In 7 Days")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
