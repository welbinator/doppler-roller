package com.apexbranding.dopplerroller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var spinnerProject: Spinner
    private lateinit var spinnerConfig: Spinner
    private lateinit var btnRefresh: Button
    private lateinit var statusCard: MaterialCardView
    private lateinit var tvStatusCard: TextView
    private lateinit var tvTokenSuffix: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnCreate: Button
    private lateinit var btnDelete: Button
    private lateinit var tvStatus: TextView

    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // In-memory lists for spinner data
    private val projectSlugs = mutableListOf<String>()
    private val configNames = mutableListOf<String>()

    // Suppress spinner callbacks during programmatic updates
    private var suppressProjectListener = false
    private var suppressConfigListener = false

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
        spinnerProject = findViewById(R.id.spinnerProject)
        spinnerConfig = findViewById(R.id.spinnerConfig)
        btnRefresh = findViewById(R.id.btnRefresh)
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

        btnRefresh.setOnClickListener { loadProjects() }
        btnCreate.setOnClickListener { createToken() }
        btnDelete.setOnClickListener { deleteToken() }

        spinnerProject.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (suppressProjectListener) return
                val slug = projectSlugs.getOrNull(pos) ?: return
                loadConfigs(slug)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        if (prefs.getString("personal_token", null) != null) {
            showMainScreen()
        } else {
            showSetupScreen()
        }

        // Check for updates in background — non-blocking
        checkForUpdate()
    }

    // ─── Update Checker ─────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doCheckForUpdate() }
            result ?: return@launch  // null = no update or error; stay silent

            val (remoteTag, apkUrl) = result
            val rootView = window.decorView.rootView
            Snackbar.make(rootView, "Update available ($remoteTag) — tap to download", Snackbar.LENGTH_INDEFINITE)
                .setAction("Download") {
                    downloadAndInstall(apkUrl, remoteTag)
                }
                .show()
        }
    }

    /** Returns Pair(tag, apkUrl) if an update is available, null otherwise. Never throws. */
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
            null  // silently ignore any failure
        }
    }

    private fun downloadAndInstall(apkUrl: String, tag: String) {
        lifecycleScope.launch {
            val apkFile = withContext(Dispatchers.IO) { doDownloadApk(apkUrl, tag) }
            if (apkFile == null) {
                setStatus("Download failed", true)
                return@launch
            }
            launchInstaller(apkFile)
        }
    }

    private fun doDownloadApk(url: String, tag: String): File? {
        return try {
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body ?: return null
            val contentLength = body.contentLength()

            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: cacheDir
            val file = File(dir, "doppler-roller-$tag.apk")

            FileOutputStream(file).use { out ->
                val source = body.byteStream()
                val buf = ByteArray(8192)
                var downloaded = 0L
                var lastPct = -1
                var n: Int
                while (source.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (contentLength > 0) {
                        val pct = (downloaded * 100 / contentLength).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            runOnUiThread { setStatus("Downloading update… $pct%", false) }
                        }
                    }
                }
            }
            runOnUiThread { setStatus("", false) }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun launchInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ─── UI helpers ─────────────────────────────────────────────────────────────

    private fun showSetupScreen() {
        setupLayout.visibility = View.VISIBLE
        mainLayout.visibility = View.GONE
    }

    private fun showMainScreen() {
        setupLayout.visibility = View.GONE
        mainLayout.visibility = View.VISIBLE
        updateMainUI()
        loadProjects()
    }

    private fun updateMainUI() {
        val tokenKey = prefs.getString("service_token_key", null)
        val hasToken = tokenKey != null
        val activeProject = prefs.getString("active_project", null)
        val activeConfig = prefs.getString("active_config", null)

        if (hasToken) {
            statusCard.setCardBackgroundColor(getColor(R.color.status_green))
            tvStatusCard.text = getString(R.string.status_active)
            tvTokenSuffix.visibility = View.VISIBLE
            val projectInfo = if (activeProject != null && activeConfig != null) "$activeProject / $activeConfig  " else ""
            tvTokenSuffix.text = "${projectInfo}…${tokenKey!!.takeLast(8)}"
            btnCopy.visibility = View.VISIBLE
            btnCopy.isEnabled = true
            btnCopy.alpha = 1.0f
        } else {
            statusCard.setCardBackgroundColor(getColor(R.color.status_red))
            tvStatusCard.text = getString(R.string.status_inactive)
            tvTokenSuffix.visibility = View.GONE
            btnCopy.visibility = View.GONE
        }

        // Spinners disabled when token is active
        spinnerProject.isEnabled = !hasToken
        spinnerProject.alpha = if (!hasToken) 1.0f else 0.5f
        spinnerConfig.isEnabled = !hasToken
        spinnerConfig.alpha = if (!hasToken) 1.0f else 0.5f
        btnRefresh.isEnabled = !hasToken
        btnRefresh.alpha = if (!hasToken) 1.0f else 0.5f

        val projectSelected = projectSlugs.isNotEmpty() && spinnerProject.selectedItemPosition >= 0
        val configSelected = configNames.isNotEmpty() && spinnerConfig.selectedItemPosition >= 0

        btnCreate.isEnabled = !hasToken && projectSelected && configSelected
        btnCreate.alpha = if (!hasToken && projectSelected && configSelected) 1.0f else 0.4f

        btnDelete.isEnabled = hasToken
        btnDelete.alpha = if (hasToken) 1.0f else 0.4f
    }

    private fun loadProjects() {
        setStatus("Loading projects…", false)
        btnRefresh.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doFetchProjects() }
            btnRefresh.isEnabled = prefs.getString("service_token_key", null) == null

            if (result.success) {
                suppressProjectListener = true
                projectSlugs.clear()
                projectSlugs.addAll(result.items)

                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, projectSlugs)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerProject.adapter = adapter
                suppressProjectListener = false

                setStatus("", false)

                // Load configs for first project
                if (projectSlugs.isNotEmpty()) {
                    loadConfigs(projectSlugs[0])
                } else {
                    setStatus("No projects found", true)
                    updateMainUI()
                }
            } else {
                setStatus(result.message, true)
                updateMainUI()
            }
        }
    }

    private fun loadConfigs(projectSlug: String) {
        setStatus("Loading configs…", false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doFetchConfigs(projectSlug) }

            suppressConfigListener = true
            configNames.clear()
            configNames.addAll(result.items)

            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, configNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerConfig.adapter = adapter
            suppressConfigListener = false

            if (result.success) {
                setStatus("", false)
            } else {
                setStatus(result.message, true)
            }
            updateMainUI()
        }
    }

    private fun createToken() {
        val projectPos = spinnerProject.selectedItemPosition
        val configPos = spinnerConfig.selectedItemPosition
        val project = projectSlugs.getOrNull(projectPos) ?: run {
            setStatus("Select a project first", true)
            return
        }
        val config = configNames.getOrNull(configPos) ?: run {
            setStatus("Select a config first", true)
            return
        }

        setBusy(true)
        setStatus("Creating token…", false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doCreateToken(project, config) }
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
                    .remove("active_project")
                    .remove("active_config")
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

    private data class ListResult(val success: Boolean, val message: String, val items: List<String>)

    private fun doFetchProjects(): ListResult {
        return try {
            val personalToken = prefs.getString("personal_token", null)
                ?: return ListResult(false, "No personal token saved", emptyList())
            val credential = Credentials.basic(personalToken, "")

            val req = Request.Builder()
                .url("https://api.doppler.com/v3/projects?per_page=100")
                .addHeader("Authorization", credential)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return ListResult(false, "Failed to fetch projects: ${resp.code}", emptyList())
            }

            val arr = JSONObject(body).getJSONArray("projects")
            val slugs = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                slugs.add(arr.getJSONObject(i).getString("slug"))
            }
            ListResult(true, "", slugs)
        } catch (e: Exception) {
            ListResult(false, "Error: ${e.message}", emptyList())
        }
    }

    private fun doFetchConfigs(project: String): ListResult {
        return try {
            val personalToken = prefs.getString("personal_token", null)
                ?: return ListResult(false, "No personal token saved", emptyList())
            val credential = Credentials.basic(personalToken, "")

            val req = Request.Builder()
                .url("https://api.doppler.com/v3/configs?project=${project}&per_page=100")
                .addHeader("Authorization", credential)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return ListResult(false, "Failed to fetch configs: ${resp.code}", emptyList())
            }

            val arr = JSONObject(body).getJSONArray("configs")
            val names = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                names.add(arr.getJSONObject(i).getString("name"))
            }
            ListResult(true, "", names)
        } catch (e: Exception) {
            ListResult(false, "Error: ${e.message}", emptyList())
        }
    }

    private fun doCreateToken(project: String, config: String): OpResult {
        return try {
            val personalToken = prefs.getString("personal_token", null)
                ?: return OpResult(false, "No personal token saved")
            val credential = Credentials.basic(personalToken, "")

            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            val body = JSONObject().apply {
                put("project", project)
                put("config", config)
                put("name", "hermes-$timestamp")
                put("access", "read")
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val req = Request.Builder()
                .url("https://api.doppler.com/v3/configs/config/tokens")
                .addHeader("Authorization", credential)
                .post(body)
                .build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return OpResult(false, "Failed to create token: ${resp.code} $respBody")
            }

            val tokenObj = JSONObject(respBody).getJSONObject("token")
            val newKey = tokenObj.getString("key")
            val newSlug = tokenObj.getString("slug")

            prefs.edit()
                .putString("service_token_key", newKey)
                .putString("service_token_slug", newSlug)
                .putString("active_project", project)
                .putString("active_config", config)
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
            val tokenSlug = prefs.getString("service_token_slug", null)
                ?: return OpResult(false, "No token slug saved")
            val project = prefs.getString("active_project", null)
                ?: return OpResult(false, "No active project saved")
            val config = prefs.getString("active_config", null)
                ?: return OpResult(false, "No active config saved")

            val credential = Credentials.basic(personalToken, "")

            val req = Request.Builder()
                .url("https://api.doppler.com/v3/configs/config/tokens/$tokenSlug?project=$project&config=$config")
                .addHeader("Authorization", credential)
                .delete()
                .build()

            val resp = client.newCall(req).execute()
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
