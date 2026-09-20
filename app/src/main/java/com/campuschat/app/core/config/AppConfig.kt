package com.campuschat.app.core.config

import com.campuschat.app.BuildConfig

object AppConfig {
    // Supabase Credentials - Public anon key only! NO SERVICE ROLE KEY!
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY

    // Project Constants
    const val MAX_ATTACHMENT_SIZE_MB = 15
    const val MAX_ATTACHMENT_SIZE_BYTES = MAX_ATTACHMENT_SIZE_MB * 1024 * 1024
    const val PLATFORM_NAME = "android"
}
