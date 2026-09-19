package com.opensolr.photos.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language of the app (Cip, 2026-09-20): the phone's own language by default, falling back to
 * English when it is not one the app speaks, or one the owner picks on Me.
 *
 * On Android 13 and later the choice is Android's own per-app language, so it also shows in the
 * system settings and every part of the app follows it. Before that, the choice is kept here and
 * applied to the activity's and the application's resources as they are created.
 */
object AppLanguage {

    /** The languages the app is translated into: tag and the language's own name for itself. */
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

    /** The language picked on Me, or "" for the phone's own. */
    fun chosen(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeIf { !it.isEmpty }?.get(0)?.language?.takeIf { tag -> SUPPORTED.any { it.first == tag } } ?: ""
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        }

    /**
     * Picks [tag] ("" = the phone's own). On Android 13+ Android itself recreates the screens in
     * the new language; before that the activity is recreated here.
     */
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

    /**
     * [base] with the chosen language applied, for Android before 13; unchanged otherwise, or when
     * the phone's own language is in force.
     */
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
