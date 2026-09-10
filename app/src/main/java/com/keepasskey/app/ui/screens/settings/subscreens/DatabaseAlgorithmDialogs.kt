package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 加密算法 / KDF 派生算法选择对话框（ISSUE-P3-29：自 `DatabaseSettingsDialogs.kt` 拆出，纯结构性拆分）。
 */

// 对话框 1：加密算法选择
@Composable
internal fun CipherAlgorithmDialog(
    currentAlgorithm: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlgorithmOptionDialog(
        titleRes = R.string.dbset_cipher_dialog_title,
        options = listOf(
            "ChaCha20-Poly1305 (256-bit)" to R.string.dbset_cipher_chacha_desc,
            "AES-256 (KDBX 4.1)" to R.string.dbset_cipher_aes_desc,
            "Twofish (256-bit)" to R.string.dbset_cipher_twofish_desc
        ),
        isSelected = { currentAlgorithm.startsWith(it.split(" ")[0]) },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

// 对话框 2：KDF 密钥派生算法选择
@Composable
internal fun KdfAlgorithmDialog(
    currentAlgorithm: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlgorithmOptionDialog(
        titleRes = R.string.dbset_kdf_dialog_title,
        options = listOf(
            "Argon2id" to R.string.dbset_kdf_argon2id_desc,
            "Argon2d" to R.string.dbset_kdf_argon2d_desc,
            "AES-KDF" to R.string.dbset_kdf_aeskdf_desc
        ),
        isSelected = { currentAlgorithm == it },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

/** 算法单选对话框通用骨架：加密算法与 KDF 派生算法共用（结构与原内联实现一致） */
@Composable
private fun AlgorithmOptionDialog(
    titleRes: Int,
    options: List<Pair<String, Int>>,
    isSelected: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (name, desc) ->
                    val selected = isSelected(name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onSelect(name)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                onSelect(name)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}
