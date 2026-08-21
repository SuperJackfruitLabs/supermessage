package dev.supermessage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermessage.kit.Session

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    val vm: SessionViewModel = viewModel()
                    val phase by vm.session.phase.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) {
                        if (phase == Session.Phase.STARTING) vm.session.start()
                    }
                    RootScaffold(phase = phase)
                }
            }
        }
    }
}
