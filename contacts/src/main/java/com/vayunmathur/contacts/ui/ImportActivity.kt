package com.vayunmathur.contacts.ui
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.MainActivity
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.contacts.R

class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: return

        val contacts = contentResolver.openInputStream(uri)?.use { input ->
            VcfUtils.parseContacts(input)
        } ?: emptyList()

        setContent {
            DynamicTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ImportScreen(contacts) {
                        contacts.forEach { it.save(this@ImportActivity, it.details, ContactDetails.empty()) }
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun ImportScreen(contacts: List<Contact>, onImport: () -> Unit) {
    AppScaffold(
        title = stringResource(R.string.import_contacts),
        floatingActionButton = {
            FloatingActionButton(onClick = onImport) {
                IconSave()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(contacts, key = { "${it.name.value}|${it.details.phoneNumbers.firstOrNull()?.number ?: ""}|${it.details.emails.firstOrNull()?.address ?: ""}" }) { contact ->
                Card(modifier = Modifier.padding(4.dp).fillMaxSize()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = contact.name.value, style = MaterialTheme.typography.titleMedium)
                        if (contact.details.phoneNumbers.isNotEmpty()) {
                            Text(text = contact.details.phoneNumbers.joinToString(", ") { it.number }, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (contact.details.emails.isNotEmpty()) {
                            Text(text = contact.details.emails.joinToString(", ") { it.address }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
