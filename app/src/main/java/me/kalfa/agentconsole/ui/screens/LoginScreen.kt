package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import me.kalfa.agentconsole.di.DependencyContainer

/**
 * Gates the whole app behind a Supabase session.
 * Mock mode (no configured client) passes straight through.
 * RLS note: data appears only for users registered in console_agents.
 */
@Composable
fun AuthGate(content: @Composable () -> Unit) {
    val client = DependencyContainer.supabaseClient
    if (client == null) {
        content() // mock mode
        return
    }
    val status by client.auth.sessionStatus.collectAsState()
    when (status) {
        is SessionStatus.Authenticated -> content()
        is SessionStatus.Initializing -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        else -> LoginScreen(client)
    }
}

@Composable
fun LoginScreen(client: SupabaseClient) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SupportAgent,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "KALFA — קונסולת נציגים",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "התחברות עם חשבון נציג מורשה",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("אימייל") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("סיסמה") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                client.auth.signInWith(Email) {
                                    this.email = email
                                    this.password = password
                                }
                                // Success flips sessionStatus -> AuthGate swaps content.
                            } catch (e: Exception) {
                                error = "ההתחברות נכשלה — בדוק אימייל/סיסמה והרשאת נציג"
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("התחבר")
                    }
                }
            }
        }
    }
}
