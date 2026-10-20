package com.theuzxl13.assistente.settings

import android.content.Context
import com.theuzxl13.assistente.domain.PreferencesPort

/** Preferências guardadas só no aparelho. Nenhuma conversa é gravada. */
class UserPreferences(context: Context) : PreferencesPort {

    private val prefs = context.getSharedPreferences("preferencias", Context.MODE_PRIVATE)

    override var speechRate: Float
        get() = prefs.getFloat("velocidade_fala", 1f)
        set(value) = prefs.edit().putFloat("velocidade_fala", value).apply()

    override var onboardingDone: Boolean
        get() = prefs.getBoolean("configuracao_concluida", false)
        set(value) = prefs.edit().putBoolean("configuracao_concluida", value).apply()
}
