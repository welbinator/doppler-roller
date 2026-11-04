package com.apexbranding.dopplerroller

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var etToken: TextInputEditText
    private lateinit var btnSaveToken: Button
    private lateinit var btnCheckUpdates: Button
    private lateinit var btnBack: Button

    private val client = OkHttpClient()

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            "doppler_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etToken = findViewById(R.id.etSettingsToken)
        btnSaveToken = findViewById(R.id.btnSaveToken)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        btnBack = findViewById(R.id.btnSettingsBack)

        // Show current token masked (just placeholder; field is passwordInputType so it shows dots)
        val savedToken = prefs.getString("personal_token", "")
        etToken.setText(savedToken)

        btnSaveToken.setOnClickListener {
            val token = etToken.text.toString().trim()
            if (token.isBlank()) {
                Toast.makeText(this, "Token cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("personal_token", token).apply()
            Toast.makeText(this, "Token saved", Toast.LENGTH_SHORT).show()
        }

        btnCheckUpdates.setOnClickListener {
            btnCheckUpdates.isEnabled = false
            btnCheckUpdates.text = "Checking…"
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { doCheckForUpdate() }
                btnCheckUpdates.isEnabled = true
                btnCheckUpdates.text = "Check for Updates"
                if (result == null) {
                    Toast.makeText(this@SettingsActivity, "Up to date", Toast.LENGTH_SHORT).show()
                } else {
                    val (tag, _) = result
                    Toast.makeText(
                        this@SettingsActivity,
                        "Update available $tag — open app to download",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun doCheckForUpdate(): Pair<String, String>? {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/welbinator/doppler-roller/releases/latest")
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val tagName = json.optString("tag_name") ?: return null
            val remoteVersion = tagName.replace("v", "").trim().toIntOrNull() ?: return null

            val localVersion = packageManager.getPackageInfo(packageName, 0).versionName
                .replace("v", "").trim().toIntOrNull() ?: return null

            if (remoteVersion <= localVersion) return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val downloadUrl = asset.optString("browser_download_url") ?: continue
                if (downloadUrl.endsWith(".apk")) {
                    apkUrl = downloadUrl
                    break
                }
            }
            apkUrl?.let { Pair(tagName, it) }
        } catch (e: Exception) {
            null
        }
    }
}
