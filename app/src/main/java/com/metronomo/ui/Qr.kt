package com.metronomo.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Escolhe o endereço da rede local (192.168.x.x, 10.x.x.x, 172.16-31.x.x) para o QR code;
 * se não houver, o primeiro da lista.
 */
fun pickLanUrl(urls: List<String>): String? {
    fun isPrivate(u: String): Boolean {
        val host = u.removePrefix("http://").substringBefore(':')
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
    return urls.firstOrNull { isPrivate(it) } ?: urls.firstOrNull()
}

/** Desenha [text] como QR code (preto sobre branco, com a margem exigida pelos leitores). */
@Composable
fun QrImage(text: String, size: Dp = 220.dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (e: Exception) {
            null
        }
    } ?: return
    Canvas(
        modifier
            .size(size)
            .background(Color.White)
            .padding(12.dp),
    ) {
        val n = matrix.width
        val cell = this.size.width / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/**
 * Abre o leitor de QR code do Google Play Services (não precisa de permissão de câmera no app).
 * Devolve o texto lido em [onResult] ou uma mensagem em [onError].
 */
fun scanQr(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
        .build()
    val scanner = GmsBarcodeScanning.getClient(context, options)
    scanner.startScan()
        .addOnSuccessListener { barcode ->
            val raw = barcode.rawValue
            if (raw.isNullOrBlank()) onError("QR code vazio.") else onResult(raw)
        }
        .addOnCanceledListener { /* o usuário fechou o leitor */ }
        .addOnFailureListener { e ->
            onError("Não foi possível abrir o leitor de QR code (${e.message ?: "erro"}). Digite o endereço.")
        }
}
