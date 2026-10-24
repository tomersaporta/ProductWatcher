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
    private val navy = Color.rgb(12, 46, 78)
    private val ink = Color.rgb(19, 43, 68)
    private val muted = Color.rgb(102, 123, 146)
    private val canvas = Color.rgb(245, 248, 252)
    private val blue = Color.rgb(19, 127, 165)
    private lateinit var body: LinearLayout

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Store.setFolder(this, uri.toString())
                schedule()
            } catch (e: Exception) {
                Toast.makeText(this, "Folder permission failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            render()
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 29, 52)
        window.navigationBarColor = Color.rgb(8, 29, 52)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Store.folder(this) != null) schedule()
        TelegramClient.resume(this)
        render()
    }

    override fun onResume() { super.onResume(); if (::body.isInitialized) render() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun bg(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        includeFontPadding = true
    }
    private fun pad(n: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(n)) }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(Color.WHITE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }
    private fun button(title: String, filled: Boolean = true, click: () -> Unit): TextView = label(title, 14f, if (filled) Color.WHITE else blue, true).apply {
        gravity = Gravity.CENTER
        background = bg(if (filled) blue else Color.rgb(225, 242, 250), 11)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setOnClickListener { click() }
    }
    private fun heading(s: String) { body.addView(label(s, 12f, muted, true)); body.addView(pad(9)) }
    private fun schedule() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "scan_images", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).build()
        )
    }
    private fun scanImages() {
        if (Store.folder(this) == null) Toast.makeText(this, "Choose an image folder first", Toast.LENGTH_SHORT).show()
        else {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<ScanWorker>().build())
            Toast.makeText(this, "Image scan started", Toast.LENGTH_SHORT).show()
        }
    }
    private fun share() {
        val report = "ProductWatcher local report\nProducts: ${Store.products(this).joinToString()}\nImage scan: ${Store.status(this)}\nTelegram: ${TelegramClient.state}\nNo offer history included."
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
        }, "Share report"))
    }
    private fun render() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(canvas) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(navy)
            setPadding(dp(22), dp(21), dp(22), dp(22))
        }
        header.addView(label("ProductWatcher", 27f, Color.WHITE, true))
        header.addView(pad(4))
        header.addView(label("Telegram deal monitoring", 13f, Color.rgb(187, 212, 232)))
        page.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(26))
        }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val home = button("Overview", false) { render() }
        val channels = button("Channels", false) { startActivity(Intent(this, TelegramActivity::class.java)) }
        val refresh = button("Refresh", false) { render() }
        nav.addView(home, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(channels, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(nav)
        setContentView(page)

        val connected = TelegramClient.ready
        val statusColor = if (connected) Color.rgb(219, 248, 236) else Color.rgb(255, 242, 222)
        val statusText = if (connected) Color.rgb(7, 94, 73) else Color.rgb(139, 81, 17)
        val connection = card().apply { background = bg(statusColor) }
        connection.addView(label(if (connected) "● Telegram connected" else "● Telegram not connected", 18f, statusText, true))
        connection.addView(pad(5))
        connection.addView(label(TelegramClient.state, 13f, statusText))
        connection.addView(pad(12))
        connection.addView(button(if (connected) "Manage Telegram connection" else "Connect Telegram") {
            startActivity(Intent(this, TelegramActivity::class.java))
        })
        body.addView(connection)

        heading("WATCHING")
        val watch = card()
        val products = Store.products(this)
        watch.addView(label("${products.size} tracked product${if (products.size == 1) "" else "s"}", 13f, muted))
        watch.addView(pad(9))
        products.forEach { product ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(label("◈  $product", 16f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(button("Remove", false) {
                Store.setProducts(this, Store.products(this).filterNot { it == product }); render()
            })
            watch.addView(line)
            watch.addView(pad(8))
        }
        val input = EditText(this).apply {
            hint = "Add product to watchlist"
            setSingleLine(true); textSize = 14f; setTextColor(ink); setHintTextColor(muted)
        }
        watch.addView(input)
        watch.addView(pad(8))
        watch.addView(button("+ Add product") {
            val product = input.text.toString().trim()
            if (product.isNotBlank() && product !in Store.products(this)) {
                Store.setProducts(this, Store.products(this) + product); render()
            }
        })
        body.addView(watch)

        heading("TELEGRAM SOURCE")
        val source = card()
        val sourcePrefs = getSharedPreferences("telegram_source", MODE_PRIVATE)
        val selected = sourcePrefs.getString("chat_title", null)
        source.addView(label(selected ?: "No channel selected", 18f, ink, true))
        source.addView(pad(5))
        source.addView(label(if (selected == null) "Select a subscribed channel to scan" else "Selected Telegram channel", 13f, muted))
        source.addView(pad(12))
        source.addView(button("Open channels") { startActivity(Intent(this, TelegramActivity::class.java)) })
        body.addView(source)

        heading("ACTIVITY")
        val activity = card()
        activity.addView(label("Image scan: ${Store.status(this)}", 13f, ink))
        activity.addView(pad(6))
        activity.addView(label("Telegram scanning starts automatically for the selected channel.", 12f, muted))
        activity.addView(pad(12))
        activity.addView(button("Scan local images", false) { scanImages() })
        activity.addView(pad(8))
        activity.addView(button("Share status report", false) { share() })
        body.addView(activity)

        heading("OPTIONAL IMAGE SOURCE")
        val folder = card()
        folder.addView(label(if (Store.folder(this) == null) "No local image folder" else "Local image folder configured", 14f, ink, true))
        folder.addView(pad(10))
        folder.addView(button("Choose folder", false) { pickFolder.launch(null) })
        body.addView(folder)
        body.addView(label("Direct TDLib connection · On-device Telegram photo OCR · Background catch-up", 12f, muted))
    }
}
