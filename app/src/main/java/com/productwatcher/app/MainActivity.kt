package com.productwatcher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val navy = Color.rgb(12, 46, 78)
    private val ink = Color.rgb(19, 43, 68)
    private val muted = Color.rgb(102, 123, 146)
    private val canvas = Color.rgb(245, 248, 252)
    private val blue = Color.rgb(19, 127, 165)
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val productStatus = mutableMapOf<String, TextView>()
    private val channelStatus = mutableMapOf<Long, TextView>()
    private var monitorStatus: TextView? = null
    private val liveUpdater = object : Runnable {
        override fun run() {
            productStatus.forEach { (product, view) -> view.text = Store.productReport(this@MainActivity, product) }
            channelStatus.forEach { (id, view) -> view.text = Store.channelReport(this@MainActivity, id) }
            monitorStatus?.text = if (MonitorControl.enabled(this@MainActivity))
                "● Automatic monitoring enabled · ${TelegramClient.state}" else "● Monitoring stopped"
            handler.postDelayed(this, 4000L)
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
        MonitorControl.ensureAutomatic(this)
        TelegramClient.resume(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) render()
        handler.removeCallbacks(liveUpdater)
        handler.post(liveUpdater)
    }
    override fun onPause() {
        handler.removeCallbacks(liveUpdater)
        super.onPause()
    }

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
    private fun share() {
        val report = "ProductWatcher Telegram report\nProducts: ${Store.products(this).joinToString()}\n" +
            Store.monitoredChannels(this).joinToString("\n") { (id, name) -> "$name: ${Store.channelReport(this, id)}" }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
        }, "Share report"))
    }
    private fun render() {
        productStatus.clear()
        channelStatus.clear()
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
            val telemetry = label(Store.productReport(this, product), 12f, muted)
            telemetry.setPadding(dp(19), dp(3), dp(5), dp(9))
            watch.addView(telemetry)
            productStatus[product] = telemetry
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
                Store.setProducts(this, Store.products(this) + product)
                Store.requestFullRescan(this)
                TelegramClient.scanSelectedChannel()
                render()
            }
        })
        body.addView(watch)

        heading("MONITORED TELEGRAM CHANNELS")
        val source = card()
        val monitored = Store.monitoredChannels(this)
        if (monitored.isEmpty()) source.addView(label("No monitored channels selected", 14f, muted))
        monitored.forEach { (id, name) ->
            source.addView(label(name, 17f, ink, true))
            source.addView(pad(5))
            val report = label(Store.channelReport(this, id), 13f, muted)
            source.addView(report)
            channelStatus[id] = report
            source.addView(pad(14))
        }
        source.addView(button("Manage channels") { startActivity(Intent(this, TelegramActivity::class.java)) })
        body.addView(source)

        heading("MONITOR ACTIVITY")
        val activity = card()
        monitorStatus = label(if (MonitorControl.enabled(this)) "● Automatic monitoring enabled" else "● Automatic monitoring stopped", 15f, ink, true)
        activity.addView(monitorStatus)
        activity.addView(pad(8))
        activity.addView(label("New Telegram posts are processed automatically; no local folder scanning.", 13f, muted))
        activity.addView(pad(12))
        val deleteImages = CheckBox(this).apply {
            text = "Delete downloaded Telegram images after OCR"
            textSize = 14f
            setTextColor(ink)
            isChecked = Store.deleteTelegramImages(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Store.setDeleteTelegramImages(this@MainActivity, checked) }
        }
        activity.addView(deleteImages)
        activity.addView(label("Images are deleted only after processing finishes. Failed scans keep their files for retry; Telegram posts are never deleted.", 12f, muted))
        activity.addView(pad(12))
        activity.addView(button("Rescan full Telegram history", false) {
            Store.requestFullRescan(this)
            TelegramClient.scanSelectedChannel()
            render()
        })
        activity.addView(pad(9))
        activity.addView(button("Share status report", false) { share() })
        body.addView(activity)
        body.addView(label("Direct TDLib connection · On-device Telegram photo OCR · Background catch-up", 12f, muted))
    }
}
