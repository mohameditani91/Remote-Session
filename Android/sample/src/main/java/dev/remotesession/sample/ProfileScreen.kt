package dev.remotesession.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.remotesession.remoteSessionMasked

/** Compose: nothing here needs a RemoteSession marker except the masked recovery key. The PIN field is masked on its own. */
@Composable
fun ProfileScreen() {
    var name by remember { mutableStateOf("Alex Doe") }
    var pin by remember { mutableStateOf("") }
    var newsletter by remember { mutableStateOf(true) }
    var plan by remember { mutableIntStateOf(0) }
    var likes by remember { mutableIntStateOf(0) }
    val plans = listOf("Basic", "Plus", "Pro")

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Profile", style = MaterialTheme.typography.headlineLarge) }
        item { Section("Account") }
        item { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(
                pin, { pin = it }, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().toggleable(newsletter, role = Role.Switch) { newsletter = it }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Newsletter", Modifier.weight(1f))
                Switch(newsletter, onCheckedChange = null)
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                plans.forEachIndexed { index, title ->
                    SegmentedButton(selected = plan == index, onClick = { plan = index }, shape = SegmentedButtonDefaults.itemShape(index, plans.size)) { Text(title) }
                }
            }
        }
        item { Section("Gesture-driven card") }
        item {
            Card(Modifier.fillMaxWidth().clickable { likes += 1 }) {
                Text("👍  Liked $likes times", Modifier.padding(16.dp))
            }
        }
        item { Section("Private") }
        item { Text("Recovery key: K7QD-29XF-M4LP", Modifier.remoteSessionMasked().padding(vertical = 8.dp)) }
        item { Section("History") }
        items((1..30).toList()) { entry ->
            Text("Sign-in $entry", Modifier.padding(vertical = 6.dp))
            HorizontalDivider()
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}
