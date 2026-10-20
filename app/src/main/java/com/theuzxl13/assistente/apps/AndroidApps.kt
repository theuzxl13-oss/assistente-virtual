package com.theuzxl13.assistente.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.theuzxl13.assistente.domain.AppsPort
import com.theuzxl13.assistente.domain.InstalledApp
import com.theuzxl13.assistente.domain.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Abre apps pelo nome que aparece na tela inicial (lista de apps com ícone no launcher). */
class AndroidApps(private val context: Context) : AppsPort {

    override suspend fun find(name: String): List<InstalledApp> = withContext(Dispatchers.IO) {
        val target = TextNormalizer.normalize(ALIASES[TextNormalizer.normalize(name)] ?: name)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }

        val normalized = apps.map { it to TextNormalizer.normalize(it.label) }
        val exact = normalized.filter { it.second == target }
        val prefix = normalized.filter { it.second.startsWith(target) }
        val contains = normalized.filter { it.second.contains(target) }
        exact.ifEmpty { prefix.ifEmpty { contains } }.map { it.first }.sortedBy { it.label.length }
    }

    override fun launch(app: InstalledApp): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private companion object {
        /** Apelidos comuns falados no Brasil. */
        val ALIASES = mapOf(
            "zap" to "whatsapp",
            "zapzap" to "whatsapp",
            "zap zap" to "whatsapp",
            "whats" to "whatsapp",
            "uats" to "whatsapp",
            "face" to "facebook",
            "insta" to "instagram",
            "you tube" to "youtube",
            "iutubi" to "youtube",
            "telefone" to "telefone",
            "discador" to "telefone",
            "agenda" to "contatos",
            "camera" to "camera",
            "maquina fotografica" to "camera",
            "despertador" to "relogio",
            "alarme" to "relogio",
            "configuracao" to "configuracoes",
            "ajustes" to "configuracoes"
        )
    }
}
