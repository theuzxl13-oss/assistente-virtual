package com.theuzxl13.assistente

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.theuzxl13.assistente.domain.PermissionGroup
import com.theuzxl13.assistente.domain.PermissionPort
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pede permissões pela janela padrão do Android, uma de cada vez e só quando a funcionalidade
 * vai ser usada. Precisa ser criado durante a construção da Activity (antes do onStart).
 */
class ActivityPermissions(private val activity: ComponentActivity) : PermissionPort {

    private var waiting: CancellableContinuation<Boolean>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.isNotEmpty() && results.values.all { it }
        waiting?.let { if (it.isActive) it.resume(granted) }
        waiting = null
    }

    override fun has(group: PermissionGroup): Boolean = permissionsOf(group).all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun request(group: PermissionGroup): Boolean {
        if (has(group)) return true
        return suspendCancellableCoroutine { continuation ->
            waiting = continuation
            launcher.launch(permissionsOf(group))
            continuation.invokeOnCancellation { waiting = null }
        }
    }

    private fun permissionsOf(group: PermissionGroup): Array<String> = when (group) {
        PermissionGroup.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        PermissionGroup.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        // As três ficam no mesmo grupo "Telefone": o Android mostra uma única janela.
        PermissionGroup.PHONE -> arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE
        )
        PermissionGroup.CALL_LOG -> arrayOf(Manifest.permission.READ_CALL_LOG)
    }
}
