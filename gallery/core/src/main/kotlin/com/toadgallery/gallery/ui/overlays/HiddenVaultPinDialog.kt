package com.toadgallery.gallery.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.components.ToadDisplayFont
import com.toadgallery.gallery.ui.theme.ToadTheme

/**
 * The r76 HIDDEN VAULT PIN DIALOG (the prototype's `#vault-modal`): the
 * 4-dot PIN indicator, the `3x4` tactile keypad (1-9, Clear, 0, Unlock),
 * the optional biometric trigger, and --- on a correct PIN --- a
 * transition into the encrypted private vault grid (the caller renders
 * it; this dialog reports the unlock).
 *
 * The keypad is PURE UI: the caller validates ([onPinEntered] returns
 * true for unlock) --- the vault's own sealed-PIN machinery stays in the
 * caller. A wrong PIN shakes nothing and simply clears (the honest
 * failure posture; the vault's throttle lives in the caller too).
 */
@Composable
fun HiddenVaultPinDialog(
    onDismiss: () -> Unit,
    onPinEntered: (CharArray) -> Boolean,
    onBiometric: (() -> Unit)?,
    biometricAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    var pin by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.bgSurface)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(24.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- The header: shield badge + title + close ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = palette.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Hidden Vault",
                    color = palette.textMain,
                    style = TextStyle(
                        fontFamily = ToadDisplayFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                )
                Text(
                    text = if (failed) "Wrong PIN • try again" else "Enter 4-digit PIN",
                    color = if (failed) palette.dangerText else palette.textMuted,
                    style = TextStyle(
                        fontFamily = ToadBodyFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(palette.bgSurfaceHigh)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = palette.textMain,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- The 4-dot PIN indicator ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { index ->
                val filled = index < pin.length
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) palette.primary else palette.bgSurfaceHighest,
                        )
                        .border(1.dp, palette.borderStrong, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- The 3x4 tactile keypad ----
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "→"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { key ->
                        VaultKeyButton(
                            key = key,
                            onClick = {
                                when (key) {
                                    "C" -> {
                                        pin = ""
                                        failed = false
                                    }
                                    "→" -> {
                                        if (pin.length == 4) {
                                            val entered = pin.toCharArray()
                                            if (onPinEntered(entered)) {
                                                onDismiss()
                                            } else {
                                                failed = true
                                                pin = ""
                                            }
                                        }
                                    }
                                    else -> if (pin.length < 4) {
                                        pin += key
                                        failed = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // ---- The optional biometric trigger ----
        if (biometricAvailable && onBiometric != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.secondaryContainer)
                    .clickable(onClick = onBiometric)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Unlock with biometrics",
                    color = palette.textMain,
                    style = TextStyle(
                        fontFamily = ToadBodyFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
    }
}

/** One keypad button: a digit, Clear, or Unlock. */
@Composable
private fun VaultKeyButton(
    key: String,
    onClick: () -> Unit,
) {
    val palette = ToadTheme.palette
    val isAction = key == "C" || key == "→"
    Box(
        modifier = Modifier
            .size(width = 76.dp, height = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isAction) palette.bgSurfaceHigh else palette.bgSurfaceHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (key == "C") {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Clear",
                tint = palette.textMuted,
                modifier = Modifier.size(18.dp),
            )
        } else if (key == "→") {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Unlock",
                tint = palette.primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = key,
                color = palette.textMain,
                style = TextStyle(
                    fontFamily = ToadDisplayFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}
