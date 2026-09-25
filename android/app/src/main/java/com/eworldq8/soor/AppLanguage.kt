package com.eworldq8.soor

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import com.eworldq8.soor.engine.Lang

/**
 * The language Soor shows follows the phone: the language chosen for Soor in
 * the phone's settings, else the first of the phone's own languages that Soor
 * speaks, else English.
 *
 * Android 13 and later have a per-app language setting (Settings, Apps, Soor,
 * Language), and the language button in the app writes to that same setting,
 * so the two never disagree. Older phones have no such setting, so there the
 * app keeps the choice itself.
 */
object AppLanguage {
    private const val PREFS = "soor"
    private const val KEY = "lang"

    /** The first of these language tags that Soor speaks decides, otherwise English. */
    fun pick(tags: List<String>): Lang {
        for (t in tags) {
            when (t.lowercase().substringBefore('-').substringBefore('_')) {
                "ar" -> return Lang.AR
                "en" -> return Lang.EN
            }
        }
        return Lang.EN
    }

    fun current(context: Context): Lang {
        if (Build.VERSION.SDK_INT < 33) {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            if (saved != null) return pick(listOf(saved))
        }
        val list = context.resources.configuration.locales
        return pick((0 until list.size()).map { list[it].toLanguageTag() })
    }

    fun set(context: Context, lang: Lang) {
        val tag = if (lang == Lang.AR) "ar" else "en"
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, tag) }
        }
    }
}
