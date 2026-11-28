package dev.remotesession.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.remotesession.RemoteSession
import dev.remotesession.SessionMode
import dev.remotesession.SessionState

@Composable
fun SupportScreen() {
    val context = LocalContext.current
    var relay by remember { mutableStateOf(Support.relay(context)) }
    var video by remember { mutableStateOf(Support.video(context)) }
    val running = Support.state.let { it != null && it !is SessionState.Ended }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Support", style = MaterialTheme.typography.headlineLarge)
        Text("Start a session, then read the code to someone who opened the relay's console in a browser.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            relay, { relay = it; Support.setRelay(context, it) }, label = { Text("Relay") }, singleLine = true, enabled = !running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        Column {
            Row(
                Modifier.fillMaxWidth().toggleable(video, enabled = !running, role = Role.Switch) { video = it; Support.setVideo(context, it) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Video", Modifier.weight(1f))
                Switch(video, onCheckedChange = null, enabled = !running)
            }
            Text(
                "On: H.264 video when the agent's browser can decode it. Off: JPEG stills. Applies to the next session.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { if (running) RemoteSession.end() else (context as? android.app.Activity)?.let { Support.start(it) } }, modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "End support session" else "Start support session")
        }
        Support.state?.let { Text(describe(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun describe(state: SessionState) = when (state) {
    SessionState.Connecting -> "Connecting…"
    is SessionState.WaitingForAgent -> "Code ${state.code}. Waiting for an agent."
    is SessionState.AwaitingConsent -> "${state.agent.name} is asking to join."
    is SessionState.Active -> "${state.agent.name} is ${if (state.mode == SessionMode.CONTROL) "controlling" else "viewing"}."
    is SessionState.Ended -> "Ended (${state.reason.wire})."
}
