package com.glebsbp.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RssFeed
import com.glebsbp.client.ProfileGroup
import com.glebsbp.client.ProfilesStore
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManagementDialog(
    groups: List<ProfileGroup>,
    subscriptionGroupIds: Set<String> = emptySet(),
    profilesStore: ProfilesStore,
    onDismissRequest: () -> Unit,
    onExportGroup: (ProfileGroup) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var editGroup by remember { mutableStateOf<ProfileGroup?>(null) }
    var deleteGroup by remember { mutableStateOf<ProfileGroup?>(null) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Управление папками", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (groups.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                            Text("У вас пока нет папок", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    groups.forEach { group ->
                        val isSubscription = subscriptionGroupIds.contains(group.id)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isSubscription) Icons.Filled.RssFeed else Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(group.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                }
                                Row {
                                    IconButton(onClick = { onExportGroup(group) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Share, contentDescription = "Экспорт", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                    if (!isSubscription) {
                                        IconButton(onClick = { editGroup = group }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Переименовать", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    IconButton(onClick = { deleteGroup = group }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Новая папка")
            }
        },
    )

    if (showAddDialog || editGroup != null) {
        var nameInput by remember { mutableStateOf(editGroup?.name ?: "") }
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editGroup = null
            },
            title = { Text(if (editGroup == null) "Создать папку" else "Переименовать папку") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Название папки") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val finalName = nameInput.trim()
                    val isEditing = editGroup != null
                    val isAdding = showAddDialog
                    if (finalName.isNotBlank() && (isAdding || isEditing)) {
                        val groupToSave = editGroup
                        showAddDialog = false
                        editGroup = null
                        scope.launch {
                            if (groupToSave == null) {
                                profilesStore.saveGroup(ProfileGroup(id = UUID.randomUUID().toString(), name = finalName))
                            } else {
                                profilesStore.saveGroup(groupToSave.copy(name = finalName))
                            }
                        }
                    }
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    editGroup = null
                }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (deleteGroup != null) {
        val target = deleteGroup!!
        val isSubDelete = subscriptionGroupIds.contains(target.id)
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text(if (isSubDelete) "Удалить подписку?" else "Удалить папку?") },
            text = {
                Text(
                    if (isSubDelete) {
                        "Подписка «${target.name}» и все её профили будут удалены."
                    } else {
                        "Папка «${target.name}» и все профили в ней будут удалены без возможности восстановления."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            profilesStore.deleteGroup(target.id)
                            deleteGroup = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroup = null }) { Text("Отмена") }
            }
        )
    }
}
