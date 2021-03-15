package com.github.brokenithm

import android.app.Application

class BrokenithmApplication : Application() {
    var lastServer: String
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getString("server", "") ?: ""
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putString("server", value).apply()
        }

    var enableAir: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("enable_air", true)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("enable_air", value).apply()
        }

    var airSource: Int
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getInt("air_source", 3)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putInt("air_source", value).apply()
        }

    var simpleAir: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("simple_air", false)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("simple_air", value).apply()
        }

    var showDelay: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("show_delay", false)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("show_delay", value).apply()
        }

    var enableVibrate: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("enable_vibrate", true)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("enable_vibrate", value).apply()
        }

    var tcpMode: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("tcp_mode", false)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("tcp_mode", value).apply()
        }

    companion object {
        private const val settings_preference = "settings"
    }
}