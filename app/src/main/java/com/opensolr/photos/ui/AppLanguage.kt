package com.opensolr.photos.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguage {

    val SUPPORTED = listOf(
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "ro" to "Română",
        "ja" to "日本語",
        "zh" to "中文",
    )

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    fun chosen(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeIf { !it.isEmpty }?.get(0)?.language?.takeIf { tag -> SUPPORTED.any { it.first == tag } } ?: ""
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        }

    fun choose(activity: Activity, tag: String) {
        val clean = tag.takeIf { t -> SUPPORTED.any { it.first == t } } ?: ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (clean.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(clean)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean).commit()
            activity.recreate()
        }
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
