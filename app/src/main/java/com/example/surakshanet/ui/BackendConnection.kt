package com.example.surakshanet.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.*
import com.example.surakshanet.BuildConfig
import com.example.surakshanet.network.BackendClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BackendConnection() {
    var status by remember { mutableStateOf("Ready to check") }
    val scope = rememberCoroutineScope()
    val configured = BuildConfig.API_BASE_URL.isNotBlank()

    StatusCard(
        title = "Backend API Cloud Server",
        detail = if (configured) status else "No external server configured (Running in local standalone mode).",
        icon = if (!configured) Icons.Rounded.CloudOff else if (status.contains("available", ignoreCase = true)) Icons.Rounded.CloudDone else Icons.Rounded.Cloud
    )

    if (configured && status != "Checking…") {
        SurakshaOutlinedButton(
            label = "Check Cloud Server Health",
            icon = Icons.Rounded.Sync,
            click = {
                status = "Checking…"
                scope.launch {
                    status = try {
                        val health = BackendClient.api.health()
                        if (health.status == "ok" && health.service == "SurakshaNet API") {
                            "Server online and healthy · SurakshaNet API connected"
                        } else {
                            "Unexpected server response"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Server unavailable: ${e.message}"
                    }
                }
            }
        )
    }
}
