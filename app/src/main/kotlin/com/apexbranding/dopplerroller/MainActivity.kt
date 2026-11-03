package com.apexbranding.dopplerroller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Setup screen
    private lateinit var setupLayout: LinearLayout
    private lateinit var etPersonalToken: EditText
    private lateinit var btnSave: Button

    // Main screen
    private lateinit var mainLayout: LinearLayout
    private lateinit var statusCard: MaterialCardView
    private lateinit var tvStatusCard: TextView
    private lateinit var tvTokenSuffix: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnCreate: Button
    private lateinit var btnDelete: Button
    private lateinit var tvStatus: TextView

    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
        setContentView(R.layout.activity_main)

        setupLayout = findViewById(R.id.setupLayout)
        etPersonalToken = findViewById(R.id.etPersonalToken)
        btnSave = findViewById(R.id.btnSave)

        mainLayout = findViewById(R.id.mainLayout)
        statusCard = findViewById(R.id.statusCard)
        tvStatusCard = findViewById(R.id.tvStatusCard)
        tvTokenSuffix = findViewById(R.id.tvTokenSuffix)
        btnCopy = findViewById(R.id.btnCopy)
        btnCreate = findViewById(R.id.btnCreate)
        btnDelete = findViewById(R.id.btnDelete)
        tvStatus = findViewById(R.id.tvStatus)

        btnSave.setOnClickListener {
            val token = etPersonalToken.text.toString().trim()
            if (token.isBlank()) {
                Toast.makeText(this, "Please enter a personal token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("personal_token", token).apply()
            showMainScreen()
        }

        btnCopy.setOnClickListener {
            val serviceToken = prefs.getString("service_token_key", null)
            if (serviceToken == null) {
                Toast.makeText(this, "No token to copy", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("service_token", serviceToken))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        btnCreate.setOnClickListener { createToken() }
        btnDelete.setOnClickListener { deleteToken() }

        if (prefs.getString("personal_token", null) != null) {
            showMainScreen()
        } else {
            showSetupScreen()
        }
    }

    private fun showSetupScreen() {
        setupLayout.visibility = View.VISIBLE
        mainLayout.visibility = View.GONE
    }

    private fun showMainScreen() {
        setupLayout.visibility = View.GONE
        mainLayout.visibility = View.VISIBLE
        updateMainUI()
    }

    private fun updateMainUI() {
        val tokenKey = prefs.getString("service_token_key", null)
        val hasToken = tokenKey != null

        if (hasToken) {
            statusCard.setCardBackgroundColor(getColor(R.color.status_green))
            tvStatusCard.text = getString(R.string.status_active)
            tvTokenSuffix.visibility = View.VISIBLE
            tvTokenSuffix.text = "Token: …${tokenKey!!.takeLast(8)}"
            btnCopy.visibility = View.VISIBLE
            btnCopy.isEnabled = true
            btnCopy.alpha = 1.0f
        } else {
            statusCard.setCardBackgroundColor(getColor(R.color.status_red))
            tvStatusCard.text = getString(R.string.status_inactive)
            tvTokenSuffix.visibility = View.GONE
            btnCopy.visibility = View.GONE
        }

        btnCreate.isEnabled = !hasToken
        btnCreate.alpha = if (!hasToken) 1.0f else 0.4f

        btnDelete.isEnabled = hasToken
        btnDelete.alpha = if (hasToken) 1.0f else 0.4f
    }

    private fun createToken() {
        setBusy(true)
        setStatus("Creating token…", false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doCreateToken() }
            setBusy(false)
            if (result.success) {
                updateMainUI()
                setStatus("Token created. Hermes now has access.", false)
            } else {
                setStatus(result.message, true)
            }
        }
    }

    private fun deleteToken() {
        setBusy(true)
        setStatus("Deleting token…", false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doDeleteToken() }
            setBusy(false)
            if (result.success) {
                prefs.edit()
                    .remove("service_token_key")
                    .remove("service_token_slug")
                    .apply()
                updateMainUI()
                setStatus("Token deleted. Hermes no longer has access.", false)
            } else {
                setStatus(result.message, true)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        btnCreate.isEnabled = !busy
        btnDelete.isEnabled = !busy
        btnCopy.isEnabled = !busy
        if (!busy) updateMainUI()
    }

    private fun setStatus(msg: String, isError: Boolean) {
        tvStatus.text = msg
        tvStatus.setTextColor(
            if (isError) getColor(R.color.error_text) else getColor(R.color.status_default)
        )
    }

    // ─── Network ───────────────────────────────────────────────────────────────

    private fun doCreateToken(): OpResult {
        return try {
            val personalToken = prefs.getString("personal_token", null)
                ?: return OpResult(false, "No personal token saved")

            val credential = Credentials.basic(personalToken, "")

            // Get or create service account slug
            var serviceAccountSlug = prefs.getString("service_account_slug", null)
            if (serviceAccountSlug == null) {
                val body = JSONObject().apply {
                    put("name", "hermes-agent")
                    put("display_name", "Hermes Agent")
                }.toString().toRequestBody(JSON_MEDIA_TYPE)

                val req = Request.Builder()
                    .url("https://api.doppler.com/v3/workplace/service_accounts")
                    .addHeader("Authorization", credential)
                    .post(body)
                    .build()

                val resp = client.newCall(req).execute()
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    if (resp.code == 409) {
                        // Already exists — list and find it
                        val listReq = Request.Builder()
                            .url("https://api.doppler.com/v3/workplace/service_accounts")
                            .addHeader("Authorization", credential)
                            .get()
                            .build()
                        val listResp = client.newCall(listReq).execute()
                        val listBody = listResp.body?.string() ?: ""
                        if (!listResp.isSuccessful) {
                            return OpResult(false, "Failed to list service accounts: ${listResp.code}")
                        }
                        val accounts = JSONObject(listBody).getJSONArray("service_accounts")
                        var found: String? = null
                        for (i in 0 until accounts.length()) {
                            val acct = accounts.getJSONObject(i)
                            if (acct.getString("name") == "hermes-agent") {
                                found = acct.getString("slug")
                                break
                            }
                        }
                        serviceAccountSlug = found
                            ?: return OpResult(false, "Service account conflict but could not find existing")
                    } else {
                        return OpResult(false, "Failed to create service account: ${resp.code} $bodyStr")
                    }
                } else {
                    serviceAccountSlug = JSONObject(bodyStr).getJSONObject("service_account").getString("slug")
                }
                prefs.edit().putString("service_account_slug", serviceAccountSlug).apply()
            }

            // Create new token
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            val tokenBody = JSONObject().apply {
                put("name", "hermes-token-$timestamp")
                put("expires_at", JSONObject.NULL)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val tokenReq = Request.Builder()
                .url("https://api.doppler.com/v3/workplace/service_accounts/$serviceAccountSlug/tokens")
                .addHeader("Authorization", credential)
                .post(tokenBody)
                .build()

            val tokenResp = client.newCall(tokenReq).execute()
            val tokenBodyStr = tokenResp.body?.string() ?: ""
            if (!tokenResp.isSuccessful) {
                return OpResult(false, "Failed to create token: ${tokenResp.code} $tokenBodyStr")
            }

            val tokenJson = JSONObject(tokenBodyStr)
            val newTokenKey = tokenJson.getJSONObject("token").getString("key")
            val newTokenSlug = tokenJson.getJSONObject("token").getString("slug")

            prefs.edit()
                .putString("service_token_key", newTokenKey)
                .putString("service_token_slug", newTokenSlug)
                .apply()

            OpResult(true, "")
        } catch (e: Exception) {
            OpResult(false, "Error: ${e.message}")
        }
    }

    private fun doDeleteToken(): OpResult {
        return try {
            val personalToken = prefs.getString("personal_token", null)
                ?: return OpResult(false, "No personal token saved")
            val serviceAccountSlug = prefs.getString("service_account_slug", null)
                ?: return OpResult(false, "No service account slug saved")
            val tokenSlug = prefs.getString("service_token_slug", null)
                ?: return OpResult(false, "No token slug saved")

            val credential = Credentials.basic(personalToken, "")

            val deleteReq = Request.Builder()
                .url("https://api.doppler.com/v3/workplace/service_accounts/$serviceAccountSlug/tokens/$tokenSlug")
                .addHeader("Authorization", credential)
                .delete()
                .build()

            val resp = client.newCall(deleteReq).execute()
            if (!resp.isSuccessful && resp.code != 404) {
                return OpResult(false, "Delete failed: ${resp.code}")
            }

            OpResult(true, "")
        } catch (e: Exception) {
            OpResult(false, "Error: ${e.message}")
        }
    }

    data class OpResult(val success: Boolean, val message: String)
}
