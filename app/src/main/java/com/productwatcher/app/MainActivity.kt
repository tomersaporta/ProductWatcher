package com.productwatcher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(11, 18, 32)
    private val surface = Color.rgb(23, 34, 52)
    private val muted = Color.rgb(166, 180, 203)
    private val accent = Color.rgb(89, 222, 189)
    private lateinit var content: LinearLayout

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Store.setFolder(this, uri.toString())
                schedule()
            } catch (e: Exception) { Toast.makeText(this, "Folder permission failed: ${e.message}", Toast.LENGTH_LONG).show() }
            render()
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Store.folder(this) != null) schedule()
        render()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 18): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private fun space(n: Int) { content.addView(View(this), LinearLayout.LayoutParams(1, dp(n))) }
    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(surface)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }
    private fun action(label: String, primary: Boolean = true, onClick: () -> Unit): TextView = text(label, 15f, if (primary) bg else Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        background = shape(if (primary) accent else Color.rgb(50, 65, 87), 12)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        setOnClickListener { onClick() }
    }
    private fun schedule() {
        val work = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("scan_images", ExistingPeriodicWorkPolicy.KEEP, work)
    }
    private fun scan() {
        if (Store.folder(this) == null) Toast.makeText(this, "Choose an image folder first", Toast.LENGTH_SHORT).show()
        else {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<ScanWorker>().build())
            Toast.makeText(this, "Scan started. Refresh after a moment.", Toast.LENGTH_LONG).show()
        }
    }
    private fun share() {
        val report = buildString {
            appendLine("ProductWatcher — local report")
            appendLine("Watched products: ${Store.products(this@MainActivity).joinToString(", ")}")
            appendLine("Last scan: ${Store.status(this@MainActivity)}")
            appendLine("Source: local Telegram image folder")
            appendLine("Note: no individual offer history is stored in this version; prices and images are not included.")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
        }, "Share report with ChatGPT"))
    }
    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); fillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(32))
        }
        scroll.addView(content); setContentView(scroll)
        content.addView(text("PRODUCTWATCHER", 12f, accent, true))
        space(10)
        content.addView(text("Your deals. In focus.", 29f, Color.WHITE, true))
        space(6)
        content.addView(text("Track product offers from images saved on your phone.", 14f, muted))
        space(25)

        val status = panel()
        status.addView(text("MONITOR STATUS", 12f, muted, true))
        status.addView(text(if (Store.folder(this) == null) "● Setup required" else "● Monitoring enabled", 21f, if (Store.folder(this) == null) Color.rgb(255, 194, 116) else accent, true))
        status.addView(text("Last scan: ${Store.status(this)}", 13f, muted))
        status.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        status.addView(action("Scan images now") { scan() })
        status.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        status.addView(action("Refresh status", false) { render() })
        content.addView(status)

        val source = panel()
        source.addView(text("IMAGE SOURCE", 12f, muted, true))
        source.addView(text(if (Store.folder(this) == null) "No folder selected" else "Telegram image folder connected", 18f, Color.WHITE, true))
        source.addView(text("Local folder access only · No automatic cloud upload", 13f, muted))
        source.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        source.addView(action("Choose image folder", false) { pickFolder.launch(null) })
        content.addView(source)

        val watch = panel()
        watch.addView(text("WATCHLIST", 12f, muted, true))
        watch.addView(text("${Store.products(this).size} tracked products", 20f, Color.WHITE, true))
        Store.products(this).forEach { product ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
            row.addView(text("◉  $product", 15f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(action("Remove", false) {
                Store.setProducts(this, Store.products(this).filterNot { it == product }); render()
            })
            watch.addView(row)
        }
        val input = EditText(this).apply {
            hint = "Add a product name"; setHintTextColor(muted); setTextColor(Color.WHITE)
            isSingleLine = true; textSize = 15f
        }
        watch.addView(input)
        watch.addView(action("+ Add to watchlist") {
            val name = input.text.toString().trim()
            if (name.isNotEmpty() && name !in Store.products(this)) {
                Store.setProducts(this, Store.products(this) + name); render()
            }
        })
        content.addView(watch)

        val integration = panel()
        integration.addView(text("CHATGPT INTEGRATION", 12f, muted, true))
        integration.addView(text("Share a scan summary", 19f, Color.WHITE, true))
        integration.addView(text("Share a local status report to ChatGPT or another app. Live sync is not connected yet.", 13f, muted))
        integration.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        integration.addView(action("Share report", false) { share() })
        content.addView(integration)
        space(8)
        content.addView(text("Prototype · Latin OCR only · Prices are unverified · Background scans are approximate", 12f, muted))
    }
}
