package com.glebsbp.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.glebsbp.client.ConnectionProfile
import com.glebsbp.client.PeerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportProfileSheet(
    profile: ConnectionProfile,
    serverDtlsPort: Int = 56000,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val link = remember(profile, serverDtlsPort) {
        val exportPeer = PeerAddress.ensurePort(profile.peer, serverDtlsPort)
        val nameEsc = URLEncoder.encode(profile.name, "UTF-8")
        val peerEsc = URLEncoder.encode(exportPeer, "UTF-8")
        val hashesEsc = URLEncoder.encode(profile.vkHashes, "UTF-8")
        val passEsc = URLEncoder.encode(profile.password, "UTF-8")
        "glebsbp://config?name=$nameEsc&peer=$peerEsc&hashes=$hashesEsc&workers=${profile.workersPerHash}&port=${profile.listenPort}&pass=$passEsc"
    }

    LaunchedEffect(link) {
        scope.launch(Dispatchers.Default) {
            val bmp = generateQrCode(link, 600)
            withContext(Dispatchers.Main) {
                qrBitmap = bmp
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                val json = """
                    {
                        "name": "${profile.name.replace("\"", "\\\"")}",
                        "peer": "${PeerAddress.ensurePort(profile.peer, serverDtlsPort).replace("\"", "\\\"")}",
                        "vkHashes": "${profile.vkHashes.replace("\"", "\\\"")}",
                        "workersPerHash": ${profile.workersPerHash},
                        "listenPort": ${profile.listenPort},
                        "password": "${profile.password.replace("\"", "\\\"")}"
                    }
                """.trimIndent()
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray())
                }
                Toast.makeText(context, "Файл сохранен", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Экспорт подписки",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                profile.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Отсканируйте код другим устройством в приложении GLEBSBP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("GLEBSBP Profile", link))
                        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ссылка", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        createDocumentLauncher.launch("${profile.name}.conf")
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("В файл", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

internal fun generateQrCode(text: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    } catch (e: Exception) {
        null
    }
}

