package labs.magi.mobilemcp

import android.content.Context
import android.os.Build
import java.util.UUID

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("mobilemcp", Context.MODE_PRIVATE)

    fun hubUrl(ctx: Context): String = p(ctx).getString("hubUrl", "") ?: ""
    fun name(ctx: Context): String = p(ctx).getString("name", "") ?: ""
    fun token(ctx: Context): String = p(ctx).getString("token", "") ?: ""
    fun autoConnect(ctx: Context): Boolean = p(ctx).getBoolean("autoConnect", true)

    /** Stable per-install identity sent in the hello; the hub keys reconnects on it. */
    fun deviceId(ctx: Context): String {
        val existing = p(ctx).getString("deviceId", null)
        if (!existing.isNullOrEmpty()) return existing
        val id = UUID.randomUUID().toString().replace("-", "")
        p(ctx).edit().putString("deviceId", id).apply()
        return id
    }

    fun displayName(ctx: Context): String = name(ctx).ifBlank { Build.MODEL ?: "Android" }

    fun save(ctx: Context, hubUrl: String, name: String, token: String, autoConnect: Boolean) {
        p(ctx).edit().putString("hubUrl", hubUrl.trim()).putString("name", name.trim()).putString("token", token.trim()).putBoolean("autoConnect", autoConnect).apply()
    }
}
