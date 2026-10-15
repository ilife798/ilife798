package com.github.ilife798

expect class PersistentStorage {
    fun saveString(
        key: String,
        value: String,
    )

    fun getString(key: String): String?

    fun saveBoolean(
        key: String,
        value: Boolean,
    )

    fun getBoolean(key: String): Boolean

    fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean

    fun saveInt(
        key: String,
        value: Int,
    )

    fun getInt(
        key: String,
        defaultValue: Int,
    ): Int
}

object StorageKeys {
    const val PHONE = "account_phone"
    const val IS_LOGGED_IN = "account_is_logged_in"
    const val POINTS_LOGIN_DONE = "account_points_login_done"
    const val UID = "account_uid"
    const val TOKEN = "account_token"
    const val APP_TOKEN = "account_app_token"
    const val ACCOUNT_LIST = "account_list"
    const val CURRENT_ACCOUNT_INDEX = "account_current_index"
    const val DYNAMIC_COLOR = "setting_dynamic_color"
    const val CUSTOM_COLOR = "setting_custom_color"
    const val PALETTE_STYLE = "setting_palette_style"
    const val SEED_COLOR = "setting_seed_color"
    const val FLOATING_NAV = "setting_floating_nav"
    const val APP_BLUR = "setting_app_blur"
    const val PREDICTIVE_BACK = "setting_predictive_back"
    const val THEME_MODE = "setting_theme_mode"
    const val HOME_DEVICE_TYPE = "home_device_type"
    const val DEVICE_NOTES = "device_notes"
    const val DEVICE_ALIASES = "device_aliases"
    const val GITHUB_PROXY = "setting_github_proxy"
    const val DEVELOPER_MODE = "setting_developer_mode"
    const val CHECK_UPDATE_ON_START = "setting_check_update_on_start"
    const val DISCLAIMER_SHOWN = "setting_disclaimer_shown"
    const val LAUNCH_COUNT = "stats_launch_count"
    const val SPONSOR_PROMPT_STOPPED = "setting_sponsor_prompt_stopped"
}

object AppStorage {
    lateinit var instance: PersistentStorage
}
