package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit

/**
 * Minimal manual DI: this is a small single-developer app (SPEC-BASE.md
 * Section 4.1 "avoid excessive abstraction"), so a full DI framework
 * (Hilt/Koin) is not justified yet for one API client.
 */
object NetworkModule {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }

    val vaultApi: VaultApi by lazy { retrofit.create(VaultApi::class.java) }
}
