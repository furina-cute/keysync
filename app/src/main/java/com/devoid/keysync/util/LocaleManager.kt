package com.devoid.keysync.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleManager {
    
    fun setLocale(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        
        val config = context.resources.configuration
        config.setLocale(locale)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }
    
    fun getLocale(context: Context): String {
        return context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
            .getString("locale", "en") ?: "en"
    }
    
    fun saveLocale(context: Context, language: String) {
        context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
            .edit()
            .putString("locale", language)
            .apply()
    }
}
