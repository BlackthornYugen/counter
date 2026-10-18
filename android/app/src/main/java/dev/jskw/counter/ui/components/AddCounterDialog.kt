package dev.jskw.counter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jskw.counter.ui.theme.CounterTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCounterDialog(
    onDismiss: () -> Unit,
    onCreateCounter: (title: String, initialCount: Int) -> Unit,
    onImportCounter: ((link: String) -> Unit)? = null
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Create, 1: Import
    var title by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("0") }
    var linkInput by remember { mutableStateOf("") }

    val presets = listOf(
        "Trick-or-Treaters",
        "Bottles of Water",
        "Glasses of Water",
        "Pages Read",
        "Workouts"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (selectedTab == 0) "Create New Counter" else "Import Counter Link",
                    style = MaterialTheme.typography.headlineSmall
                )
                if (onImportCounter != null) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Create New") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Import Link") }
                        )
                    }
                }
            }
        },
        text = {
            if (selectedTab == 0) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Presets:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        presets.forEach { preset ->
                            AssistChip(
                                onClick = { title = preset },
                                label = { Text(preset) },
                                colors = if (title == preset) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Counter Title") },
                        placeholder = { Text("e.g., Trick-or-Treaters") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Initial Count:",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val count = countText.toIntOrNull() ?: 0

                            IconButton(
                                onClick = {
                                    if (count > 0) countText = (count - 1).toString()
                                },
                                enabled = count > 0
                            ) {
                                Icon(Icons.Rounded.Remove, contentDescription = "Decrement initial count")
                            }

                            OutlinedTextField(
                                value = countText,
                                onValueChange = { input ->
                                    if (input.isEmpty() || input.all { it.isDigit() }) {
                                        countText = input
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(0.35f)
                            )

                            IconButton(
                                onClick = { countText = (count + 1).toString() }
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Increment initial count")
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Paste a shared counter link below:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it },
                        label = { Text("Share Link or Hashtag") },
                        placeholder = { Text("e.g. #Counter#read#uuid1#write#uuid2") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Link, contentDescription = null)
                        },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (selectedTab == 0) {
                TextButton(
                    onClick = {
                        val initialCount = countText.toIntOrNull() ?: 0
                        if (title.isNotBlank()) {
                            onCreateCounter(title.trim(), initialCount)
                        }
                    },
                    enabled = title.isNotBlank()
                ) {
                    Text("Create")
                }
            } else {
                TextButton(
                    onClick = {
                        if (linkInput.isNotBlank() && onImportCounter != null) {
                            onImportCounter(linkInput.trim())
                        }
                    },
                    enabled = linkInput.isNotBlank()
                ) {
                    Text("Import")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun AddCounterDialogPreview() {
    CounterTheme {
        AddCounterDialog(
            onDismiss = {},
            onCreateCounter = { _, _ -> },
            onImportCounter = {}
        )
    }
}
