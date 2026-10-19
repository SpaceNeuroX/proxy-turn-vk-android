package com.glebsbp.client

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Один управляемый сервер — полный набор данных для деплоя (SSH) и
 * админ-доступа (admin HTTP API), по образцу того, что раньше хранилось в
 * SettingsStore как единственный "deploy*" набор полей. Список таких
 * серверов теперь источник истины и для мульти-деплоя (DeployTab), и для
 * просмотра доступов по серверам (AdminTab).
 */
data class ManagedServer(
    val id: String,
    val name: String,
    val ip: String,
    val sshLogin: String,
    val sshPassword: String,
    val sshUseKey: Boolean,
    val sshPrivateKey: String,
    val sshKeyPassphrase: String,
    val sshKeyName: String,
    val sshPort: String,
    val dns1: String,
    val dns2: String,
    val adminPassword: String,
    val adminApiToken: String = "",
    val adminCertPin: String = "",
    val dtlsPort: Int = 56000,
    val wgPort: Int = 56001,
    val manualPortsEnabled: Boolean = false,
)

/**
 * Список серверов пользователя (мульти-сервер). Хранение — тот же паттерн,
 * что и ProfilesStore: одна DataStore-строка со списком id через запятую,
 * плюс отдельные ключи "server_<field>_<id>" на каждое поле. Секреты
 * (SSH-пароль, приватный ключ, пароль от ключа, admin-пароль) шифруются тем
 * же SecureStringStore (AndroidKeyStore/AES-GCM), что и ProfilesStore для
 * пароля подписки — никакой новой криптосхемы.
 */
class ServersStore(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)

    companion object {
        private val Context.dataStore by preferencesDataStore("servers")
        private const val IDS_KEY = "servers_ids"
        private fun idsKey() = stringPreferencesKey(IDS_KEY)
        private val LEGACY_MIGRATED = booleanPreferencesKey("legacy_server_migrated")

        private fun nameKey(id: String) = stringPreferencesKey("server_name_$id")
        private fun ipKey(id: String) = stringPreferencesKey("server_ip_$id")
        private fun sshLoginKey(id: String) = stringPreferencesKey("server_ssh_login_$id")
        private fun sshPasswordEncKey(id: String) = stringPreferencesKey("server_ssh_password_enc_$id")
        private fun sshUseKeyKey(id: String) = booleanPreferencesKey("server_ssh_use_key_$id")
        private fun sshPrivateKeyEncKey(id: String) = stringPreferencesKey("server_ssh_private_key_enc_$id")
        private fun sshKeyPassphraseEncKey(id: String) = stringPreferencesKey("server_ssh_key_passphrase_enc_$id")
        private fun sshKeyNameKey(id: String) = stringPreferencesKey("server_ssh_key_name_$id")
        private fun sshPortKey(id: String) = stringPreferencesKey("server_ssh_port_$id")
        private fun dns1Key(id: String) = stringPreferencesKey("server_dns1_$id")
        private fun dns2Key(id: String) = stringPreferencesKey("server_dns2_$id")
        private fun adminPasswordEncKey(id: String) = stringPreferencesKey("server_admin_password_enc_$id")
        private fun adminApiTokenEncKey(id: String) = stringPreferencesKey("server_admin_api_token_enc_$id")
        private fun adminCertPinKey(id: String) = stringPreferencesKey("server_admin_cert_pin_$id")
        private fun dtlsPortKey(id: String) = intPreferencesKey("server_dtls_port_$id")
        private fun wgPortKey(id: String) = intPreferencesKey("server_wg_port_$id")
        private fun manualPortsKey(id: String) = booleanPreferencesKey("server_manual_ports_$id")
    }

    private val dataStore = appContext.dataStore
    private val secureStore = SecureStringStore(appContext)
    private val migrationMutex = Mutex()

    val servers: Flow<List<ManagedServer>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> parseServers(prefs) }

    private fun parseServers(prefs: androidx.datastore.preferences.core.Preferences): List<ManagedServer> {
        val idsRaw = prefs[idsKey()] ?: ""
        if (idsRaw.isBlank()) return emptyList()
        val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return ids.map { id -> serverFromPrefs(prefs, id) }
    }

    private fun serverFromPrefs(prefs: androidx.datastore.preferences.core.Preferences, id: String): ManagedServer {
        return ManagedServer(
            id = id,
            name = prefs[nameKey(id)] ?: "",
            ip = prefs[ipKey(id)] ?: "",
            sshLogin = prefs[sshLoginKey(id)] ?: "",
            sshPassword = secureStore.decrypt(prefs[sshPasswordEncKey(id)]) ?: "",
            sshUseKey = prefs[sshUseKeyKey(id)] ?: false,
            sshPrivateKey = secureStore.decrypt(prefs[sshPrivateKeyEncKey(id)]) ?: "",
            sshKeyPassphrase = secureStore.decrypt(prefs[sshKeyPassphraseEncKey(id)]) ?: "",
            sshKeyName = prefs[sshKeyNameKey(id)] ?: "",
            sshPort = prefs[sshPortKey(id)] ?: "22",
            dns1 = prefs[dns1Key(id)] ?: "1.1.1.1",
            dns2 = prefs[dns2Key(id)] ?: "1.0.0.1",
            adminPassword = secureStore.decrypt(prefs[adminPasswordEncKey(id)]) ?: "",
            adminApiToken = secureStore.decrypt(prefs[adminApiTokenEncKey(id)]) ?: "",
            adminCertPin = prefs[adminCertPinKey(id)] ?: "",
            dtlsPort = prefs[dtlsPortKey(id)] ?: 56000,
            wgPort = prefs[wgPortKey(id)] ?: 56001,
            manualPortsEnabled = prefs[manualPortsKey(id)] ?: false,
        )
    }

    suspend fun getServerOnce(id: String): ManagedServer? {
        val prefs = dataStore.data.first()
        val idsRaw = prefs[idsKey()] ?: ""
        val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (id !in ids) return null
        return serverFromPrefs(prefs, id)
    }

    suspend fun addServer(server: ManagedServer): ManagedServer = withContext(Dispatchers.IO) {
        val toSave = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
        saveServer(toSave)
        toSave
    }

    suspend fun updateServer(server: ManagedServer) = withContext(Dispatchers.IO) {
        saveServer(server)
    }

    private suspend fun saveServer(server: ManagedServer) {
        dataStore.edit { prefs ->
            val idsRaw = prefs[idsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!ids.contains(server.id)) ids.add(server.id)
            prefs[idsKey()] = ids.joinToString(",")

            prefs[nameKey(server.id)] = server.name
            prefs[ipKey(server.id)] = server.ip
            prefs[sshLoginKey(server.id)] = server.sshLogin
            prefs[sshPasswordEncKey(server.id)] = secureStore.encrypt(server.sshPassword)
            prefs[sshUseKeyKey(server.id)] = server.sshUseKey
            prefs[sshPrivateKeyEncKey(server.id)] = secureStore.encrypt(server.sshPrivateKey)
            prefs[sshKeyPassphraseEncKey(server.id)] = secureStore.encrypt(server.sshKeyPassphrase)
            prefs[sshKeyNameKey(server.id)] = server.sshKeyName
            prefs[sshPortKey(server.id)] = server.sshPort
            prefs[dns1Key(server.id)] = server.dns1
            prefs[dns2Key(server.id)] = server.dns2
            prefs[adminPasswordEncKey(server.id)] = secureStore.encrypt(server.adminPassword)
            prefs[adminApiTokenEncKey(server.id)] = secureStore.encrypt(server.adminApiToken)
            prefs[adminCertPinKey(server.id)] = server.adminCertPin
            prefs[dtlsPortKey(server.id)] = server.dtlsPort
            prefs[wgPortKey(server.id)] = server.wgPort
            prefs[manualPortsKey(server.id)] = server.manualPortsEnabled
        }
    }

    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val idsRaw = prefs[idsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            ids.remove(id)
            prefs[idsKey()] = ids.joinToString(",")
            prefs.remove(nameKey(id))
            prefs.remove(ipKey(id))
            prefs.remove(sshLoginKey(id))
            prefs.remove(sshPasswordEncKey(id))
            prefs.remove(sshUseKeyKey(id))
            prefs.remove(sshPrivateKeyEncKey(id))
            prefs.remove(sshKeyPassphraseEncKey(id))
            prefs.remove(sshKeyNameKey(id))
            prefs.remove(sshPortKey(id))
            prefs.remove(dns1Key(id))
            prefs.remove(dns2Key(id))
            prefs.remove(adminPasswordEncKey(id))
            prefs.remove(adminApiTokenEncKey(id))
            prefs.remove(adminCertPinKey(id))
            prefs.remove(dtlsPortKey(id))
            prefs.remove(wgPortKey(id))
            prefs.remove(manualPortsKey(id))
        }
    }

    suspend fun reorderServers(newOrder: List<String>) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val actualIdsRaw = prefs[idsKey()] ?: ""
            val actualIds = actualIdsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

            val subsetIndices = actualIds.mapIndexedNotNull { index, id -> if (newOrder.contains(id)) index else null }.sorted()
            if (subsetIndices.size == newOrder.size) {
                for (i in subsetIndices.indices) {
                    actualIds[subsetIndices[i]] = newOrder[i]
                }
                prefs[idsKey()] = actualIds.joinToString(",")
            } else if (actualIds.isEmpty() || newOrder.containsAll(actualIds)) {
                prefs[idsKey()] = newOrder.joinToString(",")
            }
        }
    }

    /**
     * Одноразовая миграция старого одиночного набора "deploy*" полей из
     * SettingsStore в первый ManagedServer — только если список серверов
     * ещё пуст и в SettingsStore есть непустой deployIp. Флаг
     * LEGACY_MIGRATED защищает от повторной миграции (например, если
     * пользователь потом сам удалит единственный сервер из списка — старые
     * поля SettingsStore не должны "воскреснуть").
     */
    suspend fun migrateLegacyServerIfNeeded() = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            val prefs = dataStore.data.first()
            if (prefs[LEGACY_MIGRATED] == true) return@withLock
            val idsRaw = prefs[idsKey()] ?: ""
            val hasServers = idsRaw.split(',').map { it.trim() }.any { it.isNotEmpty() }
            if (hasServers) {
                // Список уже не пуст (создан вручную) — просто помечаем миграцию
                // выполненной, ничего не переносим.
                dataStore.edit { it[LEGACY_MIGRATED] = true }
                return@withLock
            }

            val legacyIp = settings.deployIp.first()
            if (legacyIp.isBlank()) {
                dataStore.edit { it[LEGACY_MIGRATED] = true }
                return@withLock
            }

            val legacy = ManagedServer(
                id = UUID.randomUUID().toString(),
                name = legacyIp,
                ip = legacyIp,
                sshLogin = settings.deployLogin.first(),
                sshPassword = settings.deployPassword.first(),
                sshUseKey = settings.deploySshUseKey.first(),
                sshPrivateKey = settings.deploySshPrivateKey.first(),
                sshKeyPassphrase = settings.deploySshKeyPassphrase.first(),
                sshKeyName = settings.deploySshKeyName.first(),
                sshPort = settings.deploySshPort.first().ifBlank { "22" },
                dns1 = settings.deployDns1.first(),
                dns2 = settings.deployDns2.first(),
                adminPassword = settings.deployMainPassword.first(),
                dtlsPort = settings.serverDtlsPort.first(),
                wgPort = settings.serverWgPort.first(),
                manualPortsEnabled = settings.manualPortsEnabled.first(),
            )
            saveServer(legacy)
            dataStore.edit { it[LEGACY_MIGRATED] = true }
        }
    }
}
