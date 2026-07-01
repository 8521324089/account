package com.kb.ledger

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kb.ledger.data.AppDatabase
import com.kb.ledger.data.dao.PersonBalance
import com.kb.ledger.data.model.Person
import com.kb.ledger.data.model.TransactionType
import com.kb.ledger.util.BackupManager
import com.kb.ledger.util.ContactManager
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6200EE),
                    secondary = Color(0xFF03DAC6),
                    error = Color(0xFFB00020),
                    surface = Color.White,
                    background = Color(0xFFF5F5F5)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LedgerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val contactManager = remember { ContactManager(context) }
    val backupManager = remember { BackupManager(context) }

    val persons by db.personDao().getAllPersons().collectAsState(initial = emptyList())
    val allBalances by db.transactionDao().getAllBalances().collectAsState(initial = emptyList())
    val totalGive by db.transactionDao().getTotalGive().collectAsState(initial = 0.0)
    
    val todayStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val totalReceiveToday by db.transactionDao().getTotalReceiveToday(todayStart).collectAsState(initial = 0.0)
    val recentPeople by db.transactionDao().getRecentPeople().collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedPerson by remember { mutableStateOf<Person?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showTransactionFlow by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { scope.launch { backupManager.exportData(it) } }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { backupManager.importData(it) } }
    }

    fun syncContacts() {
        scope.launch {
            val phoneContacts = contactManager.fetchPhoneContacts()
            db.personDao().insertPersons(phoneContacts)
            Toast.makeText(context, "Synced ${phoneContacts.size} contacts", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.READ_CONTACTS] == true) {
            syncContacts()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("LedgerStandalone", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
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
                        scope.launch { drawerState.close() }
                        exportLauncher.launch("ledger_backup.json")
                    },
                    icon = { Icon(Icons.Default.Share, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Import Backup") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        importLauncher.launch(arrayOf("application/json"))
                    },
                    icon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                )
            }
        }
    ) {
        if (selectedPerson == null) {
            MainHomeScreen(
                persons = persons,
                balances = allBalances,
                totalGive = totalGive ?: 0.0,
                totalReceiveToday = totalReceiveToday ?: 0.0,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onPersonClick = { selectedPerson = it },
                onAddPersonClick = { showAddDialog = true },
                onMenuClick = { scope.launch { drawerState.open() } },
                onStartTransaction = { showTransactionFlow = true }
            )
        } else {
            TransactionScreen(
                person = selectedPerson!!,
                transactions = db.transactionDao().getTransactionsForPerson(selectedPerson!!.id).collectAsState(initial = emptyList()).value,
                onBack = { selectedPerson = null },
                onAddTransaction = { amount, type, note, imageUri ->
                    scope.launch {
                        db.transactionDao().insertTransaction(
                            com.kb.ledger.data.model.Transaction(
                                personId = selectedPerson!!.id,
                                amount = amount,
                                type = type,
                                note = note,
                                imageUri = imageUri
                            )
                        )
                    }
                }
            )
        }
    }

    if (showAddDialog) {
        AddPersonDialog(
            initialName = searchQuery,
            onDismiss = { showAddDialog = false },
            onAdd = { name, phone, saveToContacts ->
                scope.launch {
                    db.personDao().insertPerson(Person(name = name, phone = phone))
                    if (saveToContacts) {
                        contactManager.saveToPhoneContacts(name, phone)
                    }
                    showAddDialog = false
                    searchQuery = ""
                }
            }
        )
    }

    if (showTransactionFlow) {
        TransactionSearchDialog(
            recentPeople = recentPeople,
            allPeople = persons,
            onDismiss = { showTransactionFlow = false },
            onSelect = { 
                selectedPerson = it
                showTransactionFlow = false
            },
            onCreateNew = {
                showTransactionFlow = false
                showAddDialog = true
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHomeScreen(
    persons: List<Person>,
    balances: List<PersonBalance>,
    totalGive: Double,
    totalReceiveToday: Double,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onPersonClick: (Person) -> Unit,
    onAddPersonClick: () -> Unit,
    onMenuClick: () -> Unit,
    onStartTransaction: () -> Unit
) {
    val filteredPersons = persons.filter {
        it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery)
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
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent
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
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("NEW ENTRY") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Dashboard Summary
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Total Give", style = MaterialTheme.typography.labelSmall)
                        Text("₹${totalGive}", style = MaterialTheme.typography.titleLarge, color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Received Today", style = MaterialTheme.typography.labelSmall)
                        Text("₹${totalReceiveToday}", style = MaterialTheme.typography.titleLarge, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (filteredPersons.isEmpty() && searchQuery.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = onAddPersonClick) {
                        Text("Add '${searchQuery}' as New Customer")
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredPersons) { person ->
                    val balance = balances.find { it.personId == person.id }?.balance ?: 0.0
                    PersonListItem(person = person, balance = balance, onClick = { onPersonClick(person) })
                }
            }
        }
    }
}

@Composable
fun PersonListItem(person: Person, balance: Double, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(person.phone, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            
            Column(horizontalAlignment = Alignment.End) {
                val absBalance = kotlin.math.abs(balance)
                if (balance == 0.0) {
                    Text("Settled", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(if (balance > 0) "You Get" else "You Give", style = MaterialTheme.typography.labelSmall, color = if (balance > 0) Color(0xFF4CAF50) else Color.Red)
                    Text("₹$absBalance", style = MaterialTheme.typography.titleMedium, color = if (balance > 0) Color(0xFF4CAF50) else Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionSearchDialog(
    recentPeople: List<Person>,
    allPeople: List<Person>,
    onDismiss: () -> Unit,
    onSelect: (Person) -> Unit,
    onCreateNew: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = allPeople.filter { it.name.contains(query, ignoreCase = true) || it.phone.contains(query) }

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
                        item { Text("Recent Customers", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp)) }
                        items(recentPeople) { person ->
                            ListItem(
                                headlineContent = { Text(person.name) },
                                supportingContent = { Text(person.phone) },
                                modifier = Modifier.clickable { onSelect(person) }
                            )
                        }
                        item { HorizontalDivider() }
                    }
                    
                    item { Text("Search Results", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp)) }
                    items(filtered) { person ->
                        ListItem(
                            headlineContent = { Text(person.name) },
                            supportingContent = { Text(person.phone) },
                            modifier = Modifier.clickable { onSelect(person) }
                        )
                    }
                    
                    if (filtered.isEmpty()) {
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
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
        title = { Text("Add New Customer") },
        text = {
            Column {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                TextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveToContacts, onCheckedChange = { saveToContacts = it })
                    Text("Save to Phone Contacts")
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onAdd(name, phone, saveToContacts) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionScreen(
    person: Person,
    transactions: List<com.kb.ledger.data.model.Transaction>,
    onBack: () -> Unit,
    onAddTransaction: (Double, com.kb.ledger.data.model.TransactionType, String?, String?) -> Unit
) {
    var showAddTxDialog by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(com.kb.ledger.data.model.TransactionType.GIVE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(person.name, style = MaterialTheme.typography.titleLarge)
                        Text(person.phone, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            selectedType = com.kb.ledger.data.model.TransactionType.GIVE
                            showAddTxDialog = true
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("YOU GAVE ₹", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            selectedType = com.kb.ledger.data.model.TransactionType.RECEIVE
                            showAddTxDialog = true
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("YOU GOT ₹", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFFF8F9FA))) {
            val balance = transactions.sumOf { if (it.type == com.kb.ledger.data.model.TransactionType.GIVE) -it.amount else it.amount }
            
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (balance >= 0) "Total you will get" else "Total you will give", style = MaterialTheme.typography.labelSmall)
                    Text("₹${kotlin.math.abs(balance)}", style = MaterialTheme.typography.headlineLarge, color = if (balance >= 0) Color(0xFF4CAF50) else Color.Red, fontWeight = FontWeight.ExtraBold)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(transactions) { tx ->
                    TransactionItem(tx)
                }
            }
        }

        if (showAddTxDialog) {
            AddTransactionDialog(
                initialType = selectedType,
                onDismiss = { showAddTxDialog = false },
                onAdd = { amount, type, note, imageUri ->
                    onAddTransaction(amount, type, note, imageUri)
                    showAddTxDialog = false
                }
            )
        }
    }
}

@Composable
fun TransactionItem(tx: com.kb.ledger.data.model.Transaction) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a").format(tx.createdAt), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                if (!tx.note.isNullOrEmpty()) {
                    Text(tx.note, style = MaterialTheme.typography.bodyMedium)
                }
                if (tx.imageUri != null) {
                    AsyncImage(
                        model = tx.imageUri,
                        contentDescription = "Bill",
                        modifier = Modifier.size(60.dp).padding(top = 4.dp).background(Color.LightGray)
                    )
                }
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "₹${tx.amount}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (tx.type == com.kb.ledger.data.model.TransactionType.GIVE) Color.Red else Color(0xFF4CAF50)
                )
                Text(
                    if (tx.type == com.kb.ledger.data.model.TransactionType.GIVE) "Gave" else "Got",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tx.type == com.kb.ledger.data.model.TransactionType.GIVE) Color.Red else Color(0xFF4CAF50)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    initialType: com.kb.ledger.data.model.TransactionType,
    onDismiss: () -> Unit,
    onAdd: (Double, com.kb.ledger.data.model.TransactionType, String?, String?) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(initialType) }
    var note by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        imageUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${type.name} Entry") },
        text = {
            Column {
                TextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Enter Amount") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    prefix = { Text("₹") }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FilterChip(
                        selected = type == com.kb.ledger.data.model.TransactionType.GIVE,
                        onClick = { type = com.kb.ledger.data.model.TransactionType.GIVE },
                        label = { Text("GIVE") }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    FilterChip(
                        selected = type == com.kb.ledger.data.model.TransactionType.RECEIVE,
                        onClick = { type = com.kb.ledger.data.model.TransactionType.RECEIVE },
                        label = { Text("RECEIVE") }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TextField(value = note, onValueChange = { note = it }, label = { Text("Add Note (Optional)") }, modifier = Modifier.fillMaxWidth())
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedButton(
                    onClick = { launcher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (imageUri == null) "Attach Bill/Image" else "Change Image")
                }
                
                imageUri?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = "Attached",
                        modifier = Modifier.size(80.dp).padding(top = 8.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (amount.isNotEmpty()) onAdd(amount.toDoubleOrNull() ?: 0.0, type, note, imageUri?.toString()) }) { Text("Save Entry") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
