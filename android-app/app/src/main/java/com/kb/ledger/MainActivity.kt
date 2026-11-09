package com.kb.ledger

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import com.kb.ledger.util.ContactSearchHelper
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.kb.ledger.data.dao.PersonBalanceEntity
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.util.BackupManager
import com.kb.ledger.util.BackupFeedbackHelper
import com.kb.ledger.util.ContactManager
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import com.kb.ledger.ui.AnalyticsDialog
import com.kb.ledger.ui.RecurringRulesDialog
import com.kb.ledger.ui.ExportStatementDialog
import com.kb.ledger.ui.PaymentReminderDialog
import com.kb.ledger.util.ThemePreferenceManager
import com.kb.ledger.util.ThemeMode
import com.kb.ledger.util.BiometricSecurityManager
import com.kb.ledger.util.RecurringScheduleEngine
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.viewmodel.HomeViewModel
import com.kb.ledger.viewmodel.TransactionViewModel
import com.kb.ledger.ui.BillImageSourceDialog
import com.kb.ledger.ui.BillImagePreviewDialog
import com.kb.ledger.ui.ReceiptCapturePreviewDialog
import com.kb.ledger.ui.ReceiptSourceType
import com.kb.ledger.util.BillImageManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themePreferenceManager = remember { ThemePreferenceManager(this) }
            var themeMode by remember { mutableStateOf(themePreferenceManager.getThemeMode()) }

            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = Color(0xFF4F8EF7),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFF1E3A68),
                    onPrimaryContainer = Color(0xFFD6E4FF),
                    secondary = Color(0xFF38BDF8),
                    onSecondary = Color(0xFF0C243B),
                    secondaryContainer = Color(0xFF163E59),
                    onSecondaryContainer = Color(0xFFBAE6FD),
                    tertiary = Color(0xFF34D399),
                    onTertiary = Color(0xFF064E3B),
                    tertiaryContainer = Color(0xFF065F46),
                    onTertiaryContainer = Color(0xFFA7F3D0),
                    error = Color(0xFFF87171),
                    onError = Color(0xFF450A0A),
                    errorContainer = Color(0xFF451111),
                    onErrorContainer = Color(0xFFFFD1D1),
                    background = Color(0xFF0F141C),
                    onBackground = Color(0xFFF1F5F9),
                    surface = Color(0xFF181F2A),
                    onSurface = Color(0xFFF1F5F9),
                    surfaceVariant = Color(0xFF222B38),
                    onSurfaceVariant = Color(0xFF94A3B8),
                    surfaceContainer = Color(0xFF1C2431),
                    surfaceContainerHigh = Color(0xFF263040),
                    surfaceContainerHighest = Color(0xFF2E394C),
                    outline = Color(0xFF3B485C),
                    outlineVariant = Color(0xFF283241)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF1D4ED8),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFDBEAFE),
                    onPrimaryContainer = Color(0xFF1E3A8A),
                    secondary = Color(0xFF0284C7),
                    onSecondary = Color.White,
                    secondaryContainer = Color(0xFFE0F2FE),
                    onSecondaryContainer = Color(0xFF0369A1),
                    tertiary = Color(0xFF059669),
                    onTertiary = Color.White,
                    tertiaryContainer = Color(0xFFD1FAE5),
                    onTertiaryContainer = Color(0xFF065F46),
                    error = Color(0xFFDC2626),
                    onError = Color.White,
                    errorContainer = Color(0xFFFEE2E2),
                    onErrorContainer = Color(0xFF991B1B),
                    background = Color(0xFFF8FAFC),
                    onBackground = Color(0xFF0F172A),
                    surface = Color.White,
                    onSurface = Color(0xFF0F172A),
                    surfaceVariant = Color(0xFFF1F5F9),
                    onSurfaceVariant = Color(0xFF64748B),
                    surfaceContainer = Color(0xFFF8FAFC),
                    surfaceContainerHigh = Color(0xFFF1F5F9),
                    surfaceContainerHighest = Color(0xFFE2E8F0),
                    outline = Color(0xFFCBD5E1),
                    outlineVariant = Color(0xFFE2E8F0)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LedgerApp(
                        activity = this,
                        themeMode = themeMode,
                        onThemeModeChange = { newMode ->
                            themeMode = newMode
                            themePreferenceManager.setThemeMode(newMode)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerApp(
    activity: FragmentActivity,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    homeViewModel: HomeViewModel = viewModel(),
    transactionViewModel: TransactionViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupManager = remember { BackupManager(context) }
    val contactManager = remember { ContactManager(context) }

    val persons by homeViewModel.persons.collectAsStateWithLifecycle()
    val activePersons by homeViewModel.activePersons.collectAsStateWithLifecycle()
    val archivedPersons by homeViewModel.archivedPersons.collectAsStateWithLifecycle()
    val dashboardActivePersons by homeViewModel.dashboardActivePersons.collectAsStateWithLifecycle()
    val dashboardArchivedPersons by homeViewModel.dashboardArchivedPersons.collectAsStateWithLifecycle()
    val lastTxTimes by homeViewModel.lastTxTimes.collectAsStateWithLifecycle()
    val filterTab by homeViewModel.filterTab.collectAsStateWithLifecycle()
    val allBalances by homeViewModel.allBalances.collectAsStateWithLifecycle()
    val totalYouGiveCents by homeViewModel.totalYouGiveCents.collectAsStateWithLifecycle()
    val totalReceiveTodayCents by homeViewModel.totalReceiveTodayCents.collectAsStateWithLifecycle()
    val recentPersons by homeViewModel.recentPersons.collectAsStateWithLifecycle()
    val backupStatus by homeViewModel.backupStatus.collectAsStateWithLifecycle()
    val authState by homeViewModel.authState.collectAsStateWithLifecycle()
    val selectedPerson by transactionViewModel.selectedPerson.collectAsStateWithLifecycle()
    val transactions by transactionViewModel.transactions.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showTransactionFlow by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val biometricManager = remember { BiometricSecurityManager(context) }
    var isAppLockEnabled by remember { mutableStateOf(biometricManager.isAppLockEnabled()) }
    var isUnlocked by remember { mutableStateOf(!isAppLockEnabled || BiometricSecurityManager.isSessionUnlocked) }
    var showAnalyticsDialog by remember { mutableStateOf(false) }
    var showRecurringDialog by remember { mutableStateOf(false) }
    var analyticsTransactions by remember { mutableStateOf<List<com.kb.ledger.data.model.Transaction>>(emptyList()) }

    val recurringRules by remember {
        AppDatabase.getDatabase(context).recurringRuleDao().getAllRules()
    }.collectAsStateWithLifecycle(emptyList())

    // Run due recurring schedules automatically on startup and heal unpromoted receipts
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val dueRules = db.recurringRuleDao().getActiveDueRules(System.currentTimeMillis())
            for (rule in dueRules) {
                val (txs, updatedRule) = RecurringScheduleEngine.processRule(rule, System.currentTimeMillis())
                for (tx in txs) {
                    db.transactionDao().insertTransaction(tx)
                }
                db.recurringRuleDao().updateRule(updatedRule)
            }
            try {
                val receiptsDir = File(context.filesDir, "receipts")
                com.kb.ledger.util.BackupManager.recoverOrphanedStagingImages(receiptsDir, db.transactionDao())
            } catch (e: Exception) {
                // Non-fatal: failures must never prevent ledger from opening
                com.kb.ledger.util.SafeLogger.log("StartupRecovery", "Failed to heal orphaned staging receipts", e)
            }
        }
    }

    LaunchedEffect(showAnalyticsDialog) {
        if (showAnalyticsDialog) {
            analyticsTransactions = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).transactionDao().getAllTransactions()
            }
        }
    }

    if (!isUnlocked) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Lock",
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Ledger Locked", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Authentication required to access records", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    biometricManager.authenticate(
                        activity = activity,
                        onSuccess = { isUnlocked = true },
                        onError = { _, err ->
                            Toast.makeText(context, err.toString(), Toast.LENGTH_SHORT).show()
                        }
                    )
                }) {
                    Text("Unlock with Biometrics / PIN")
                }
            }
        }
        LaunchedEffect(Unit) {
            biometricManager.authenticate(
                activity = activity,
                onSuccess = { isUnlocked = true },
                onError = { _, _ -> }
            )
        }
        return
    }

    // ── Predictable System Back-Button Navigation ────────────────────────────
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = selectedPerson != null && !drawerState.isOpen) {
        transactionViewModel.selectPerson(null)
    }

    BackHandler(enabled = selectedPerson == null && !drawerState.isOpen && searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    var showExitConfirmDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedPerson == null && !drawerState.isOpen && searchQuery.isEmpty()) {
        showExitConfirmDialog = true
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = {
                Text(
                    text = "Close Application?",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "Do you really want to close this application?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmDialog = false
                        activity.finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Exit", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitConfirmDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            BackupFeedbackHelper.resolveExportMessage(null, isCancelled = true)
            return@rememberLauncherForActivityResult
        }
        if (isExporting) return@rememberLauncherForActivityResult
        isExporting = true
        scope.launch {
            try {
                val result = backupManager.exportData(uri)
                when (val uiMsg = BackupFeedbackHelper.resolveExportMessage(result)) {
                    is BackupFeedbackHelper.ExportUiMessage.Success ->
                        Toast.makeText(context, uiMsg.message, Toast.LENGTH_SHORT).show()
                    is BackupFeedbackHelper.ExportUiMessage.Warning ->
                        Toast.makeText(context, uiMsg.message, Toast.LENGTH_LONG).show()
                    is BackupFeedbackHelper.ExportUiMessage.Error ->
                        Toast.makeText(context, uiMsg.message, Toast.LENGTH_LONG).show()
                    BackupFeedbackHelper.ExportUiMessage.None -> {}
                }
            } finally {
                isExporting = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            BackupFeedbackHelper.resolveImportMessage(null, isCancelled = true)
            return@rememberLauncherForActivityResult
        }
        if (isImporting) return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            try {
                val result = backupManager.importData(uri)
                when (val uiMsg = BackupFeedbackHelper.resolveImportMessage(result)) {
                    is BackupFeedbackHelper.ImportUiMessage.Success ->
                        Toast.makeText(context, uiMsg.message, Toast.LENGTH_SHORT).show()
                    is BackupFeedbackHelper.ImportUiMessage.Warning ->
                        Toast.makeText(context, uiMsg.message, Toast.LENGTH_LONG).show()
                    is BackupFeedbackHelper.ImportUiMessage.Error ->
                        Toast.makeText(context, uiMsg.message, Toast.LENGTH_LONG).show()
                    BackupFeedbackHelper.ImportUiMessage.None -> {}
                }
            } finally {
                isImporting = false
            }
        }
    }

    fun syncContacts() {
        scope.launch {
            val phoneContacts = contactManager.fetchPhoneContacts()
            // Map to Person entities then bulk-insert
            val db = com.kb.ledger.data.AppDatabase.getDatabase(context)
            val mapped = phoneContacts.map {
                it.copy(
                    cloudId = java.util.UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    syncPending = true
                )
            }
            db.personDao().insertPersons(mapped)
            Toast.makeText(context, "Synced ${phoneContacts.size} contacts", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.READ_CONTACTS] == true) syncContacts()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Box(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(20.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text("LedgerApp", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        when (val auth = authState) {
                            is com.kb.ledger.data.remote.AuthState.SignedIn -> {
                                Text(auth.email, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                                Text("Offline Mode (Cloud Sync Unavailable)", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            }
                            else -> {
                                Text("Offline Mode (Local Storage)", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                                Text("Manual SAF Backup & Restore", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Google Account Sign-Out (only displayed if an account was previously stored)
                if (authState is com.kb.ledger.data.remote.AuthState.SignedIn) {
                    val auth = authState as com.kb.ledger.data.remote.AuthState.SignedIn
                    NavigationDrawerItem(
                        label = { Text("Sign Out (${auth.email})") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                homeViewModel.signOut {
                                    Toast.makeText(context, "Signed out (local ledger preserved)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign Out") }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                // Local Storage & Backup Summary Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Local Storage & Backup",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            IconButton(
                                onClick = { homeViewModel.triggerSync() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh Local Status",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Local Backup State
                        val backupText = when (val b = backupStatus) {
                            is com.kb.ledger.backup.partition.BackupStatus.Current -> "Local backup: Current (${b.totalTransactions} txs)"
                            is com.kb.ledger.backup.partition.BackupStatus.Reconciling -> "Local backup: Reconciling..."
                            is com.kb.ledger.backup.partition.BackupStatus.Error -> "Local backup error: ${b.message}"
                            is com.kb.ledger.backup.partition.BackupStatus.Idle -> "Local backup: Saved locally"
                        }
                        Text(
                            backupText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Offline version: Cloud sync is unavailable. Use 'Export Backup' below to save an off-device copy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
                NavigationDrawerItem(
                    label = { Text("Sync Phone Contacts") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                    },
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Export Backup") },
                    selected = false,
                    onClick = {
                        if (!isExporting && !isImporting) {
                            scope.launch { drawerState.close() }
                            exportLauncher.launch(BackupManager.generateDefaultExportFilename())
                        }
                    },
                    icon = { Icon(Icons.Default.Share, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Import Backup") },
                    selected = false,
                    onClick = {
                        if (!isExporting && !isImporting) {
                            scope.launch { drawerState.close() }
                            importLauncher.launch(arrayOf("application/json"))
                        }
                    },
                    icon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NavigationDrawerItem(
                    label = { Text("Analytics & Reports") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showAnalyticsDialog = true
                    },
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Recurring Transactions") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showRecurringDialog = true
                    },
                    icon = { Icon(Icons.Default.Repeat, contentDescription = null) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                // Theme selector
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = themeMode == ThemeMode.SYSTEM,
                            onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                            label = { Text("System", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = themeMode == ThemeMode.LIGHT,
                            onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                            label = { Text("Light", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = themeMode == ThemeMode.DARK,
                            onClick = { onThemeModeChange(ThemeMode.DARK) },
                            label = { Text("Dark", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                // App lock toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("App Lock", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("Biometrics or PIN", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Switch(
                        checked = isAppLockEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                if (biometricManager.isBiometricAvailable()) {
                                    biometricManager.authenticate(
                                        activity = activity,
                                        title = "Enable App Lock",
                                        subtitle = "Confirm your identity to enable lock",
                                        onSuccess = {
                                            biometricManager.setAppLockEnabled(true)
                                            isAppLockEnabled = true
                                            Toast.makeText(context, "App lock enabled", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { _, err ->
                                            Toast.makeText(context, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    Toast.makeText(context, "No biometrics or device PIN available", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                biometricManager.authenticate(
                                    activity = activity,
                                    title = "Disable App Lock",
                                    subtitle = "Confirm your identity to disable lock",
                                    onSuccess = {
                                        biometricManager.setAppLockEnabled(false)
                                        isAppLockEnabled = false
                                        Toast.makeText(context, "App lock disabled", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { _, err ->
                                        Toast.makeText(context, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
    ) {
        if (selectedPerson == null) {
            MainHomeScreen(
                activePersons = activePersons,
                archivedPersons = archivedPersons,
                dashboardActivePersons = dashboardActivePersons,
                dashboardArchivedPersons = dashboardArchivedPersons,
                lastTxTimes = lastTxTimes,
                filterTab = filterTab,
                onFilterTabChange = { homeViewModel.setFilterTab(it) },
                balances = allBalances,
                totalYouGiveCents = totalYouGiveCents,
                totalReceiveTodayCents = totalReceiveTodayCents,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onPersonClick = { transactionViewModel.selectPerson(it) },
                onAddPersonClick = { showAddDialog = true },
                onArchivePerson = { homeViewModel.archivePerson(it) },
                onRestorePerson = { homeViewModel.restorePerson(it) },
                onDeleteZeroBalancePerson = { person ->
                    homeViewModel.deleteZeroBalancePerson(person) { res ->
                        if (res is com.kb.ledger.util.AppResult.Success) {
                            Toast.makeText(context, "Deleted ${person.name}", Toast.LENGTH_SHORT).show()
                        } else if (res is com.kb.ledger.util.AppResult.Error) {
                            Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onMenuClick = { scope.launch { drawerState.open() } },
                onStartTransaction = { showTransactionFlow = true }
            )
        } else {
            TransactionScreen(
                person = selectedPerson!!,
                transactions = transactions,
                onBack = { transactionViewModel.selectPerson(null) },
                onAddTransaction = { amountCents, type, note, imageUri, createdAt, customId ->
                    transactionViewModel.addTransaction(
                        personId = selectedPerson!!.id,
                        amountCents = amountCents,
                        type = type,
                        note = note,
                        imageUri = imageUri,
                        createdAt = createdAt,
                        customId = customId
                    )
                },
                onUpdateTransaction = { id, amountCents, type, note, imageUri, createdAt ->
                    transactionViewModel.updateTransaction(
                        id = id,
                        amountCents = amountCents,
                        type = type,
                        note = note,
                        imageUri = imageUri,
                        createdAt = createdAt
                    )
                },
                onArchivePerson = {
                    homeViewModel.archivePerson(it)
                    transactionViewModel.selectPerson(null)
                },
                onRestorePerson = {
                    homeViewModel.restorePerson(it)
                    transactionViewModel.selectPerson(it.copy(isArchived = false))
                }
            )
        }
    }

    if (showAddDialog) {
        AddPersonDialog(
            initialName = searchQuery,
            onDismiss = { showAddDialog = false },
            onAdd = { name, phone, saveToContacts ->
                homeViewModel.addPerson(name, phone) { newPerson ->
                    transactionViewModel.selectPerson(newPerson)
                }
                if (saveToContacts) {
                    scope.launch { contactManager.saveToPhoneContacts(name, phone) }
                }
                showAddDialog = false
                searchQuery = ""
            }
        )
    }

    if (showTransactionFlow) {
        TransactionSearchDialog(
            recentPeople = recentPersons,
            allPeople = persons,
            lastTxTimes = lastTxTimes,
            onDismiss = { showTransactionFlow = false },
            onSelect = {
                transactionViewModel.selectPerson(it)
                showTransactionFlow = false
            },
            onCreateNew = {
                showTransactionFlow = false
                showAddDialog = true
            }
        )
    }

    if (showAnalyticsDialog) {
        AnalyticsDialog(
            persons = persons,
            balances = allBalances,
            transactions = analyticsTransactions,
            onDismiss = { showAnalyticsDialog = false },
            onSelectPerson = {
                transactionViewModel.selectPerson(it)
                showAnalyticsDialog = false
            }
        )
    }

    if (showRecurringDialog) {
        RecurringRulesDialog(
            rules = recurringRules,
            persons = persons,
            onDismiss = { showRecurringDialog = false },
            onAddRule = { rule ->
                scope.launch(Dispatchers.IO) {
                    AppDatabase.getDatabase(context).recurringRuleDao().insertRule(rule)
                }
            },
            onUpdateRule = { rule ->
                scope.launch(Dispatchers.IO) {
                    AppDatabase.getDatabase(context).recurringRuleDao().updateRule(rule)
                }
            },
            onDeleteRule = { rule ->
                scope.launch(Dispatchers.IO) {
                    AppDatabase.getDatabase(context).recurringRuleDao().deleteRule(rule)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Home Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHomeScreen(
    activePersons: List<Person>,
    archivedPersons: List<Person>,
    dashboardActivePersons: List<Person>,
    dashboardArchivedPersons: List<Person>,
    lastTxTimes: Map<Long, Long>,
    filterTab: com.kb.ledger.viewmodel.ContactFilterTab,
    onFilterTabChange: (com.kb.ledger.viewmodel.ContactFilterTab) -> Unit,
    balances: List<PersonBalanceEntity>,
    totalYouGiveCents: Long,
    totalReceiveTodayCents: Long,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onPersonClick: (Person) -> Unit,
    onAddPersonClick: () -> Unit,
    onArchivePerson: (Person) -> Unit,
    onRestorePerson: (Person) -> Unit,
    onDeleteZeroBalancePerson: (Person) -> Unit,
    onMenuClick: () -> Unit,
    onStartTransaction: () -> Unit
) {
    var personToDelete by remember { mutableStateOf<Person?>(null) }

    // When searchQuery is blank, show only contacts with transactions (Task 4)
    // When searching, search all candidates (both with and without transactions) (Task 4)
    val candidatePersons = if (searchQuery.isBlank()) {
        if (filterTab == com.kb.ledger.viewmodel.ContactFilterTab.ACTIVE) dashboardActivePersons else dashboardArchivedPersons
    } else {
        if (filterTab == com.kb.ledger.viewmodel.ContactFilterTab.ACTIVE) activePersons else archivedPersons
    }
    val contactGroups = remember(candidatePersons, searchQuery, lastTxTimes) {
        ContactSearchHelper.filterAndGroup(candidatePersons, searchQuery, lastTxTimes)
    }

    if (personToDelete != null) {
        val target = personToDelete!!
        AlertDialog(
            onDismissRequest = { personToDelete = null },
            title = { Text("Delete Contact?") },
            text = {
                Text(
                    "Are you sure you want to delete ${target.name}${if (target.phone.isNotBlank()) " (${target.phone})" else ""}?\n\n" +
                    "This contact has a settled balance of ₹0. Deleting this contact will also remove its associated transaction history and bill-image references. This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteZeroBalancePerson(target)
                        personToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { personToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search name or number") },
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartTransaction,
                icon = { Icon(Icons.Default.Add, contentDescription = "New Entry") },
                text = { Text("NEW ENTRY", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Balanced Dashboard Summary Cards: Total You Give and Received Today
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Total You Give Card (Total outstanding owed by you)
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "Total You Give",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEF4444)
                            )
                            Text(
                                "You owe",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "₹${totalYouGiveCents / 100.0}",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // Received Today Card (Replaces "Total You Get" in that exact position)
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "Received Today",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF22C55E)
                            )
                            Text(
                                "Collected today",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "₹${totalReceiveTodayCents / 100.0}",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFF22C55E),
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Contact Filter Tabs: Active vs Archived
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterTab == com.kb.ledger.viewmodel.ContactFilterTab.ACTIVE,
                    onClick = { onFilterTabChange(com.kb.ledger.viewmodel.ContactFilterTab.ACTIVE) },
                    label = { Text("Active (${dashboardActivePersons.size})") }
                )
                FilterChip(
                    selected = filterTab == com.kb.ledger.viewmodel.ContactFilterTab.ARCHIVED,
                    onClick = { onFilterTabChange(com.kb.ledger.viewmodel.ContactFilterTab.ARCHIVED) },
                    label = { Text("Archived (${dashboardArchivedPersons.size})") }
                )
            }

            if (contactGroups.isEmpty() && searchQuery.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = onAddPersonClick) {
                        Text("Add '${searchQuery}' as New Customer")
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                contactGroups.forEach { group ->
                    if (group.isGrouped) {
                        item(key = "group_header_${group.normalizedName}") {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.People,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "${group.displayName} (${group.contacts.size} entries)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    items(group.contacts, key = { it.person.id }) { item ->
                        val balanceCents = balances.find { it.personId == item.person.id }?.balanceCents ?: 0L
                        PersonListItem(
                            person = item.person,
                            balanceCents = balanceCents,
                            disambiguationLabel = item.disambiguationLabel,
                            onClick = { onPersonClick(item.person) },
                            onDelete = if (balanceCents == 0L) { { personToDelete = item.person } } else null,
                            onRestore = if (item.person.isArchived) { { onRestorePerson(item.person) } } else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PersonListItem(
    person: Person,
    balanceCents: Long,
    disambiguationLabel: String = person.phone,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                repeatDelayMillis = 1200,
                                initialDelayMillis = 1200,
                                velocity = 35.dp
                            )
                            .semantics { contentDescription = person.name }
                    )
                    if (person.isArchived) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Archived",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                val phoneText = disambiguationLabel.trim().ifBlank { "No phone number" }
                Text(
                    text = phoneText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.wrapContentWidth()
            ) {
                if (balanceCents == 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Settled",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (onDelete != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete settled contact",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                } else {
                    val absBalance = kotlin.math.abs(balanceCents) / 100.0
                    val isPositive = balanceCents > 0
                    val statusColor = if (isPositive) Color(0xFF22C55E) else Color(0xFFEF4444)
                    Text(
                        if (isPositive) "You Get" else "You Give",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "₹$absBalance",
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (person.isArchived && onRestore != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = onRestore,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "Restore",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transaction Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionScreen(
    person: Person,
    transactions: List<com.kb.ledger.data.model.Transaction>,
    onBack: () -> Unit,
    onAddTransaction: (Long, TransactionType, String?, String?, Long, String?) -> Unit,
    onUpdateTransaction: (String, Long, TransactionType, String?, String?, Long) -> Unit,
    onArchivePerson: (Person) -> Unit,
    onRestorePerson: (Person) -> Unit
) {
    var showAddTxDialog by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(TransactionType.GIVE) }
    var editingTransaction by remember { mutableStateOf<com.kb.ledger.data.model.Transaction?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }

    val balanceCents = transactions.sumOf {
        if (it.type == TransactionType.GIVE) -it.amountCents else it.amountCents
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = person.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier
                                .basicMarquee(iterations = Int.MAX_VALUE)
                                .semantics { contentDescription = person.name }
                        )
                        val phoneDisplay = person.phone.trim().ifBlank { "No phone number" }
                        Text(
                            text = phoneDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showReminderDialog = true }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Payment Reminder")
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.Download, contentDescription = "Export Statement")
                    }
                    if (person.isArchived) {
                        IconButton(onClick = { onRestorePerson(person) }) {
                            Icon(Icons.Default.Unarchive, contentDescription = "Restore Customer")
                        }
                    } else {
                        IconButton(onClick = { onArchivePerson(person) }) {
                            Icon(Icons.Default.Archive, contentDescription = "Archive Customer")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { selectedType = TransactionType.GIVE; showAddTxDialog = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("YOU GAVE ₹", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { selectedType = TransactionType.RECEIVE; showAddTxDialog = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF22C55E),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("YOU GOT ₹", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (person.isArchived) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Archived Customer", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("Transactions and balance are preserved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRestorePerson(person) }) {
                            Text("Restore")
                        }
                    }
                }
            }

            val balanceCents = transactions.sumOf {
                if (it.type == TransactionType.GIVE) -it.amountCents else it.amountCents
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (balanceCents >= 0) "Total you will get" else "Total you will give",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "₹${kotlin.math.abs(balanceCents) / 100.0}",
                        style = MaterialTheme.typography.headlineLarge,
                        color = if (balanceCents >= 0) Color(0xFF22C55E) else Color(0xFFEF4444),
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(transactions, key = { it.id }) { tx ->
                    TransactionItem(
                        tx = tx,
                        onEditClick = { editingTransaction = tx },
                        onReplaceImage = { newUri ->
                            onUpdateTransaction(tx.id, tx.amountCents, tx.type, tx.note, newUri, tx.createdAt)
                        }
                    )
                }
            }
        }

        if (showAddTxDialog) {
            AddTransactionDialog(
                initialType = selectedType,
                person = person,
                onDismiss = { showAddTxDialog = false },
                onAdd = { amountCents, type, note, imageUri, createdAt, customId ->
                    onAddTransaction(amountCents, type, note, imageUri, createdAt, customId)
                    showAddTxDialog = false
                }
            )
        }

        if (editingTransaction != null) {
            EditTransactionDialog(
                transaction = editingTransaction!!,
                onDismiss = { editingTransaction = null },
                onSave = { id, amountCents, type, note, imageUri, createdAt ->
                    onUpdateTransaction(id, amountCents, type, note, imageUri, createdAt)
                    editingTransaction = null
                }
            )
        }

        if (showExportDialog) {
            ExportStatementDialog(
                person = person,
                transactions = transactions,
                onDismiss = { showExportDialog = false }
            )
        }

        if (showReminderDialog) {
            PaymentReminderDialog(
                person = person,
                balanceCents = balanceCents,
                onDismiss = { showReminderDialog = false }
            )
        }
    }
}

@Composable
fun TransactionItem(
    tx: com.kb.ledger.data.model.Transaction,
    onEditClick: () -> Unit,
    onReplaceImage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val billImageManager = remember { BillImageManager(context) }
    var isImageError by remember(tx.imageUri) { mutableStateOf(false) }
    var showImageSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showPreviewDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var stagedReviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewSourceType by rememberSaveable { mutableStateOf(ReceiptSourceType.CAMERA) }
    var showReviewDialog by rememberSaveable { mutableStateOf(false) }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraPath?.let { File(it) }
        if (success && file != null && file.exists() && file.length() > 0) {
            scope.launch {
                val sanitized = billImageManager.processCameraCapture(file)
                if (sanitized != null) {
                    stagedReviewPath = sanitized.absolutePath
                    reviewSourceType = ReceiptSourceType.CAMERA
                    showReviewDialog = true
                } else {
                    Toast.makeText(context, "Could not capture receipt photo", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            file?.delete()
        }
        pendingCameraPath = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val staged = billImageManager.copyUriToStagingFile(uri)
                if (staged != null) {
                    stagedReviewPath = staged.absolutePath
                    reviewSourceType = ReceiptSourceType.GALLERY
                    showReviewDialog = true
                } else {
                    Toast.makeText(context, "Could not load or validate selected image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onEditClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(tx.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (!tx.note.isNullOrEmpty()) {
                    Text(tx.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (tx.imageUri != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    if (isImageError) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .clickable { showImageSourceDialog = true }
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.BrokenImage,
                                    contentDescription = "Image unavailable",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text("Image unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Tap to replace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AsyncImage(
                                model = tx.imageUri,
                                contentDescription = "Bill (Tap to preview)",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .clickable { showPreviewDialog = true },
                                onError = { isImageError = true }
                            )
                            IconButton(
                                onClick = { showImageSourceDialog = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Replace Image",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    val amountColor = if (tx.type == TransactionType.GIVE) Color(0xFFEF4444) else Color(0xFF22C55E)
                    Text(
                        "₹${tx.amount}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = amountColor
                    )
                    Text(
                        if (tx.type == TransactionType.GIVE) "Gave" else "Got",
                        style = MaterialTheme.typography.bodySmall,
                        color = amountColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (tx.imageUri == null) {
                    IconButton(
                        onClick = { showImageSourceDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Attach Receipt",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = onEditClick) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit Transaction",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showImageSourceDialog) {
        BillImageSourceDialog(
            onDismiss = { showImageSourceDialog = false },
            onTakePhoto = {
                val file = billImageManager.createCameraStagingFile()
                pendingCameraPath = file.absolutePath
                val cameraUri = billImageManager.getCameraUri(file)
                try {
                    takePictureLauncher.launch(cameraUri)
                } catch (_: Exception) {
                    file.delete()
                    pendingCameraPath = null
                    Toast.makeText(context, "No camera application available", Toast.LENGTH_SHORT).show()
                }
            },
            onPickGallery = {
                try {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    Toast.makeText(context, "No photo picker available", Toast.LENGTH_SHORT).show()
                }
            },
            hasImage = tx.imageUri != null && !isImageError,
            onViewFullscreen = { showPreviewDialog = true },
            onRemoveImage = {
                billImageManager.deleteInternalImage(tx.id)
                onReplaceImage("")
            }
        )
    }

    if (showPreviewDialog && tx.imageUri != null) {
        BillImagePreviewDialog(
            imageModel = File(tx.imageUri!!).takeIf { it.exists() } ?: tx.imageUri!!,
            onDismiss = { showPreviewDialog = false }
        )
    }

    if (showReviewDialog && stagedReviewPath != null) {
        ReceiptCapturePreviewDialog(
            imageFile = File(stagedReviewPath!!),
            sourceType = reviewSourceType,
            onRetakeOrChooseAnother = {
                val oldPath = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (oldPath != null) {
                    billImageManager.deleteStagingFile(oldPath)
                }
                if (reviewSourceType == ReceiptSourceType.CAMERA) {
                    val file = billImageManager.createCameraStagingFile()
                    pendingCameraPath = file.absolutePath
                    val cameraUri = billImageManager.getCameraUri(file)
                    try {
                        takePictureLauncher.launch(cameraUri)
                    } catch (_: Exception) {
                        file.delete()
                        pendingCameraPath = null
                        Toast.makeText(context, "No camera application available", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    try {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No photo picker available", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onConfirmUsePhoto = {
                val path = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (path != null) {
                    scope.launch {
                        val committed = billImageManager.commitStagedImage(path, tx.id)
                        if (committed != null) {
                            onReplaceImage(committed)
                            isImageError = false
                        } else {
                            billImageManager.deleteStagingFile(path)
                            Toast.makeText(context, "Could not save receipt image", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDismiss = {
                val path = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (path != null) {
                    billImageManager.deleteStagingFile(path)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionDialog(
    transaction: com.kb.ledger.data.model.Transaction,
    onDismiss: () -> Unit,
    onSave: (id: String, amountCents: Long, type: TransactionType, note: String?, imageUri: String?, createdAt: Long) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val billImageManager = remember { BillImageManager(context) }

    var amountText by remember {
        mutableStateOf(
            if (transaction.amountCents % 100L == 0L) (transaction.amountCents / 100L).toString()
            else String.format(java.util.Locale.US, "%.2f", transaction.amountCents / 100.0)
        )
    }
    var type by remember { mutableStateOf(transaction.type) }
    var note by remember { mutableStateOf(transaction.note ?: "") }
    var imageUri by remember { mutableStateOf<Uri?>(transaction.imageUri?.let { Uri.parse(it) }) }
    var currentStagedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showImageSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showPreviewDialog by rememberSaveable { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var stagedReviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewSourceType by rememberSaveable { mutableStateOf(ReceiptSourceType.CAMERA) }
    var showReviewDialog by rememberSaveable { mutableStateOf(false) }

    var createdAt by remember { mutableStateOf(transaction.createdAt) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasUnsavedChanges by remember(amountText, type, note, imageUri, createdAt, currentStagedPath) {
        derivedStateOf {
            val originalAmountStr = if (transaction.amountCents % 100L == 0L) (transaction.amountCents / 100L).toString()
            else String.format(java.util.Locale.US, "%.2f", transaction.amountCents / 100.0)
            amountText.trim() != originalAmountStr ||
                type != transaction.type ||
                note.trim() != (transaction.note ?: "") ||
                imageUri?.toString() != transaction.imageUri ||
                currentStagedPath != null ||
                createdAt != transaction.createdAt
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraPath?.let { File(it) }
        if (success && file != null && file.exists() && file.length() > 0) {
            coroutineScope.launch {
                val sanitized = billImageManager.processCameraCapture(file)
                if (sanitized != null) {
                    stagedReviewPath = sanitized.absolutePath
                    reviewSourceType = ReceiptSourceType.CAMERA
                    showReviewDialog = true
                } else {
                    Toast.makeText(context, "Could not capture receipt photo", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            file?.delete()
        }
        pendingCameraPath = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val staged = billImageManager.copyUriToStagingFile(uri)
                if (staged != null) {
                    stagedReviewPath = staged.absolutePath
                    reviewSourceType = ReceiptSourceType.GALLERY
                    showReviewDialog = true
                } else {
                    Toast.makeText(context, "Could not load or validate selected image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val handleDismiss = {
        if (hasUnsavedChanges) {
            showDiscardConfirm = true
        } else {
            if (currentStagedPath != null) {
                billImageManager.deleteStagingFile(currentStagedPath)
            }
            onDismiss()
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard Unsaved Edits?") },
            text = { Text("You have unsaved changes. Are you sure you want to discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    if (currentStagedPath != null) {
                        billImageManager.deleteStagingFile(currentStagedPath)
                    }
                    showDiscardConfirm = false
                    onDismiss()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = handleDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit Transaction", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // GIVE / RECEIVE Toggle Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { type = TransactionType.GIVE },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (type == TransactionType.GIVE) Color(0xFFEF4444) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (type == TransactionType.GIVE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("YOU GAVE", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { type = TransactionType.RECEIVE },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (type == TransactionType.RECEIVE) Color(0xFF22C55E) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (type == TransactionType.RECEIVE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("YOU GOT", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hero Amount Field
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        if (errorMessage != null) errorMessage = null
                    },
                    label = { Text("Amount *") },
                    placeholder = { Text("0.00") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    prefix = { Text("₹ ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Date & Time Picker
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val cal = Calendar.getInstance().apply { timeInMillis = createdAt }
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val updatedCal = Calendar.getInstance().apply {
                                        timeInMillis = createdAt
                                        set(Calendar.YEAR, year)
                                        set(Calendar.MONTH, month)
                                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                    }
                                    createdAt = updatedCal.timeInMillis
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Date & Time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(createdAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Text("Change", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Note Field
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (Optional)") },
                    placeholder = { Text("e.g. Rice purchase, partial settlement") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Bill Attachment
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showImageSourceDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (imageUri == null) "Attach Bill" else "Change Bill Image")
                    }
                    if (imageUri != null) {
                        OutlinedButton(
                            onClick = {
                                if (currentStagedPath != null) {
                                    billImageManager.deleteStagingFile(currentStagedPath)
                                    currentStagedPath = null
                                }
                                imageUri = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Remove")
                        }
                    }
                }

                imageUri?.let { uri ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached Bill",
                        modifier = Modifier
                            .size(90.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .align(Alignment.CenterHorizontally)
                            .clickable { showPreviewDialog = true }
                    )
                    Text(
                        "Tap image to preview",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val parsed = amountText.toDoubleOrNull()
                    if (parsed == null || parsed <= 0.0) {
                        errorMessage = "Please enter a valid amount greater than ₹0"
                        return@Button
                    }
                    val amountCents = (parsed * 100).toLong()
                    if (amountCents <= 0L) {
                        errorMessage = "Please enter a valid amount greater than ₹0"
                        return@Button
                    }
                    coroutineScope.launch {
                        isSubmitting = true
                        val finalPath = if (currentStagedPath != null) {
                            billImageManager.commitStagedImage(currentStagedPath!!, transaction.id)
                        } else if (imageUri == null) {
                            if (transaction.imageUri != null) {
                                billImageManager.deleteInternalImage(transaction.id)
                            }
                            null
                        } else {
                            transaction.imageUri
                        }
                        onSave(
                            transaction.id,
                            amountCents,
                            type,
                            note.ifBlank { null },
                            finalPath,
                            createdAt
                        )
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = handleDismiss, enabled = !isSubmitting) {
                Text("Cancel")
            }
        }
    )

    if (showImageSourceDialog) {
        BillImageSourceDialog(
            onDismiss = { showImageSourceDialog = false },
            onTakePhoto = {
                val file = billImageManager.createCameraStagingFile()
                pendingCameraPath = file.absolutePath
                val cameraUri = billImageManager.getCameraUri(file)
                try {
                    takePictureLauncher.launch(cameraUri)
                } catch (_: Exception) {
                    file.delete()
                    pendingCameraPath = null
                    Toast.makeText(context, "No camera application available", Toast.LENGTH_SHORT).show()
                }
            },
            onPickGallery = {
                try {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    Toast.makeText(context, "No photo picker available", Toast.LENGTH_SHORT).show()
                }
            },
            hasImage = imageUri != null,
            onViewFullscreen = { showPreviewDialog = true },
            onRemoveImage = {
                if (currentStagedPath != null) {
                    billImageManager.deleteStagingFile(currentStagedPath)
                    currentStagedPath = null
                }
                imageUri = null
            }
        )
    }

    if (showPreviewDialog && imageUri != null) {
        BillImagePreviewDialog(
            imageModel = imageUri!!,
            onDismiss = { showPreviewDialog = false }
        )
    }

    if (showReviewDialog && stagedReviewPath != null) {
        ReceiptCapturePreviewDialog(
            imageFile = File(stagedReviewPath!!),
            sourceType = reviewSourceType,
            onRetakeOrChooseAnother = {
                val oldPath = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (oldPath != null) {
                    billImageManager.deleteStagingFile(oldPath)
                }
                if (reviewSourceType == ReceiptSourceType.CAMERA) {
                    val file = billImageManager.createCameraStagingFile()
                    pendingCameraPath = file.absolutePath
                    val cameraUri = billImageManager.getCameraUri(file)
                    try {
                        takePictureLauncher.launch(cameraUri)
                    } catch (_: Exception) {
                        file.delete()
                        pendingCameraPath = null
                        Toast.makeText(context, "No camera application available", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    try {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No photo picker available", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onConfirmUsePhoto = {
                val path = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (path != null) {
                    if (currentStagedPath != null && currentStagedPath != path) {
                        billImageManager.deleteStagingFile(currentStagedPath)
                    }
                    currentStagedPath = path
                    imageUri = Uri.fromFile(File(path))
                }
            },
            onDismiss = {
                val path = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (path != null) {
                    billImageManager.deleteStagingFile(path)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialogs — unchanged except parameter types updated to Long cents
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionSearchDialog(
    recentPeople: List<Person>,
    allPeople: List<Person>,
    lastTxTimes: Map<Long, Long> = emptyMap(),
    onDismiss: () -> Unit,
    onSelect: (Person) -> Unit,
    onCreateNew: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val contactGroups = remember(allPeople, query, lastTxTimes) {
        ContactSearchHelper.filterAndGroup(allPeople, query, lastTxTimes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Customer") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search name or number...") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    if (query.isEmpty() && recentPeople.isNotEmpty()) {
                        item { Text("Recent Customers", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(recentPeople) { person ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        person.name,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                    )
                                },
                                supportingContent = {
                                    val phoneText = person.phone.trim().ifBlank { "No phone number" }
                                    Text(phoneText, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                modifier = Modifier.clickable { onSelect(person) }
                            )
                        }
                        item { HorizontalDivider() }
                    }
                    item { Text("Search Results", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    contactGroups.forEach { group ->
                        if (group.isGrouped) {
                            item(key = "dlg_grp_${group.normalizedName}") {
                                Text(
                                    "${group.displayName} (${group.contacts.size} entries)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                                )
                            }
                        }
                        items(group.contacts, key = { "dlg_p_${it.person.id}" }) { item ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        item.person.name,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                    )
                                },
                                supportingContent = {
                                    val phoneText = item.disambiguationLabel.trim().ifBlank { "No phone number" }
                                    Text(phoneText, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                modifier = Modifier.clickable { onSelect(item.person) }
                            )
                        }
                    }
                    if (contactGroups.isEmpty()) {
                        item {
                            TextButton(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
                                Text("+ Create New Customer")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun AddPersonDialog(
    initialName: String = "",
    onDismiss: () -> Unit,
    onAdd: (String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf("") }
    var saveToContacts by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Customer", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Customer Name *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveToContacts, onCheckedChange = { saveToContacts = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save to Phone Contacts", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onAdd(name, phone, saveToContacts) },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    initialType: TransactionType,
    person: Person? = null,
    onDismiss: () -> Unit,
    onAdd: (Long, TransactionType, String?, String?, Long, String?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val billImageManager = remember { BillImageManager(context) }

    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(initialType) }
    var note by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var currentStagedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showImageSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showPreviewDialog by rememberSaveable { mutableStateOf(false) }
    var stagedReviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewSourceType by rememberSaveable { mutableStateOf(ReceiptSourceType.CAMERA) }
    var showReviewDialog by rememberSaveable { mutableStateOf(false) }

    var createdAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasUnsavedChanges = amountText.isNotBlank() || note.isNotBlank() || imageUri != null || currentStagedPath != null || stagedReviewPath != null

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraPath?.let { File(it) }
        if (success && file != null && file.exists() && file.length() > 0) {
            coroutineScope.launch {
                val sanitized = billImageManager.processCameraCapture(file)
                if (sanitized != null) {
                    stagedReviewPath = sanitized.absolutePath
                    reviewSourceType = ReceiptSourceType.CAMERA
                    showReviewDialog = true
                } else {
                    Toast.makeText(context, "Could not capture receipt photo", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            file?.delete()
        }
        pendingCameraPath = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val staged = billImageManager.copyUriToStagingFile(uri)
                if (staged != null) {
                    stagedReviewPath = staged.absolutePath
                    reviewSourceType = ReceiptSourceType.GALLERY
                    showReviewDialog = true
                } else {
                    Toast.makeText(context, "Could not load or validate selected image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val handleDismiss = {
        if (hasUnsavedChanges) {
            showDiscardConfirm = true
        } else {
            if (currentStagedPath != null) {
                billImageManager.deleteStagingFile(currentStagedPath)
            }
            onDismiss()
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard Entry?") },
            text = { Text("You have entered transaction details. Are you sure you want to discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    if (currentStagedPath != null) {
                        billImageManager.deleteStagingFile(currentStagedPath)
                    }
                    showDiscardConfirm = false
                    onDismiss()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = handleDismiss,
        title = {
            Column {
                Text(
                    text = if (type == TransactionType.GIVE) "Record Payment (You Gave)" else "Record Payment (You Received)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                if (person != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${person.name}${if (person.phone.isNotBlank()) " • ${person.phone}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
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
                // Clear GIVE / RECEIVE Selection Buttons with cohesive theme colors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { type = TransactionType.GIVE },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (type == TransactionType.GIVE) Color(0xFFEF4444) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (type == TransactionType.GIVE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("YOU GAVE", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { type = TransactionType.RECEIVE },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (type == TransactionType.RECEIVE) Color(0xFF22C55E) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (type == TransactionType.RECEIVE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("YOU GOT", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hero Required Amount Field
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        if (errorMessage != null) errorMessage = null
                    },
                    label = { Text("Amount *") },
                    placeholder = { Text("0.00") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    prefix = { Text("₹ ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Date & Time Picker
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val cal = Calendar.getInstance().apply { timeInMillis = createdAt }
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val updatedCal = Calendar.getInstance().apply {
                                        timeInMillis = createdAt
                                        set(Calendar.YEAR, year)
                                        set(Calendar.MONTH, month)
                                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                    }
                                    createdAt = updatedCal.timeInMillis
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CalendarToday,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Date & Time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(createdAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Text(
                            "Change",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Optional Note Field
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (Optional)") },
                    placeholder = { Text("e.g. Rice purchase, partial settlement") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Bill Attachment
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showImageSourceDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (imageUri == null) "Attach Bill (Optional)" else "Change Bill Image")
                    }
                    if (imageUri != null) {
                        OutlinedButton(
                            onClick = {
                                if (currentStagedPath != null) {
                                    billImageManager.deleteStagingFile(currentStagedPath)
                                    currentStagedPath = null
                                }
                                imageUri = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Remove")
                        }
                    }
                }

                imageUri?.let { uri ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached Bill",
                        modifier = Modifier
                            .size(90.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .align(Alignment.CenterHorizontally)
                            .clickable { showPreviewDialog = true }
                    )
                    Text(
                        "Tap image to preview",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val parsed = amountText.toDoubleOrNull()
                    if (parsed == null || parsed <= 0.0) {
                        errorMessage = "Please enter a valid amount greater than ₹0"
                        return@Button
                    }
                    val amountCents = (parsed * 100).toLong()
                    if (amountCents <= 0L) {
                        errorMessage = "Please enter a valid amount greater than ₹0"
                        return@Button
                    }
                    coroutineScope.launch {
                        isSubmitting = true
                        val newTxId = UUID.randomUUID().toString()
                        val finalImagePath = if (currentStagedPath != null) {
                            billImageManager.commitStagedImage(currentStagedPath!!, newTxId)
                        } else {
                            imageUri?.toString()
                        }
                        onAdd(amountCents, type, note.ifBlank { null }, finalImagePath, createdAt, newTxId)
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Save Entry")
            }
        },
        dismissButton = {
            TextButton(onClick = handleDismiss, enabled = !isSubmitting) {
                Text("Cancel")
            }
        }
    )

    if (showImageSourceDialog) {
        BillImageSourceDialog(
            onDismiss = { showImageSourceDialog = false },
            onTakePhoto = {
                val file = billImageManager.createCameraStagingFile()
                pendingCameraPath = file.absolutePath
                val cameraUri = billImageManager.getCameraUri(file)
                try {
                    takePictureLauncher.launch(cameraUri)
                } catch (_: Exception) {
                    file.delete()
                    pendingCameraPath = null
                    Toast.makeText(context, "No camera application available", Toast.LENGTH_SHORT).show()
                }
            },
            onPickGallery = {
                try {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    Toast.makeText(context, "No photo picker available", Toast.LENGTH_SHORT).show()
                }
            },
            hasImage = imageUri != null,
            onViewFullscreen = { showPreviewDialog = true },
            onRemoveImage = {
                if (currentStagedPath != null) {
                    billImageManager.deleteStagingFile(currentStagedPath)
                    currentStagedPath = null
                }
                imageUri = null
            }
        )
    }

    if (showPreviewDialog && imageUri != null) {
        BillImagePreviewDialog(
            imageModel = imageUri!!,
            onDismiss = { showPreviewDialog = false }
        )
    }

    if (showReviewDialog && stagedReviewPath != null) {
        ReceiptCapturePreviewDialog(
            imageFile = File(stagedReviewPath!!),
            sourceType = reviewSourceType,
            onRetakeOrChooseAnother = {
                val oldPath = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (oldPath != null) {
                    billImageManager.deleteStagingFile(oldPath)
                }
                if (reviewSourceType == ReceiptSourceType.CAMERA) {
                    val file = billImageManager.createCameraStagingFile()
                    pendingCameraPath = file.absolutePath
                    val cameraUri = billImageManager.getCameraUri(file)
                    try {
                        takePictureLauncher.launch(cameraUri)
                    } catch (_: Exception) {
                        file.delete()
                        pendingCameraPath = null
                        Toast.makeText(context, "No camera application available", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    try {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No photo picker available", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onConfirmUsePhoto = {
                val path = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (path != null) {
                    if (currentStagedPath != null && currentStagedPath != path) {
                        billImageManager.deleteStagingFile(currentStagedPath)
                    }
                    currentStagedPath = path
                    imageUri = Uri.fromFile(File(path))
                }
            },
            onDismiss = {
                val path = stagedReviewPath
                stagedReviewPath = null
                showReviewDialog = false
                if (path != null) {
                    billImageManager.deleteStagingFile(path)
                }
            }
        )
    }
}
