package dev.supermessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Sign-in, the shape iOS draws at `apple/Supermessage/LoginView.swift`.
 *
 * Username and password are ordinary composable state — they are only ever
 * useful for the one attempt in front of the reader. The homeserver is not:
 * it is hoisted, because [RosterPreferences.homeserver] is what makes it
 * survive a failed attempt rather than being thrown away with the rest of
 * the form (see that class's own KDoc for why that matters).
 *
 * Deliberately free of [dev.supermessage.kit.Session]: every value this
 * composable needs — [homeserver], [failure], [busy] — arrives as a plain
 * value, and the one thing it does ([onSignIn]) is a callback. That is what
 * lets [LoginScreenTest] exercise it without a real `Session` or `Core`.
 */
@Composable
fun LoginScreen(
    homeserver: String,
    onHomeserverChange: (String) -> Unit,
    failure: String?,
    busy: Boolean,
    onSignIn: (username: String, password: String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("supermessage", style = MaterialTheme.typography.headlineLarge)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = homeserver,
                    onValueChange = onHomeserverChange,
                    label = { Text("Homeserver") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("homeserver"),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("username"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("password"),
                )
            }

            // Only when there is one to show — the placeholder this
            // composable relies on staying null (rather than an empty
            // string) when nothing has gone wrong.
            if (failure != null) {
                Text(
                    failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().testTag("failure"),
                )
            }

            Button(
                onClick = { onSignIn(username, password) },
                // A sign-in already in flight cannot be started twice, and
                // empty fields are never worth a round trip to the
                // homeserver — the same two conditions LoginView.swift:62
                // disables its button on.
                enabled = !busy && username.isNotEmpty() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("sign-in"),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("Sign in")
                }
            }
        }
    }
}
