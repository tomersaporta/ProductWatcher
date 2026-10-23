package com.productwatcher.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.graphics.Typeface
import android.view.ViewGroup
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
    private lateinit var root: LinearLayout
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
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Store.folder(this) != null) schedule()
        render()
    }
    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("scan_images", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    private fun text(s: String, large: Boolean = false) = TextView(this).apply {
        this.text = s; textSize = if (large) 22f else 16f; setPadding(0, 12, 0, 12)
        if (large) setTypeface(null, Typeface.BOLD)
    }
    private fun button(s: String, action: () -> Unit) = Button(this).apply { text = s; setOnClickListener { action() } }
    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(text("Product Watcher", true))
        root.addView(text("Local OCR, no cloud uploads. English/Latin text recognition only in v0.1."))
        root.addView(text("Folder: ${Store.folder(this) ?: "Not selected"}"))
        root.addView(button("Select Telegram Images folder") { pickFolder.launch(null) })
        root.addView(button("Scan now") {
            if (Store.folder(this) == null) Toast.makeText(this, "Choose folder first", Toast.LENGTH_SHORT).show()
            else { WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<ScanWorker>().build()); Toast.makeText(this, "Scan queued; refresh status shortly", Toast.LENGTH_SHORT).show() }
        })
        root.addView(text("Last scan: ${Store.status(this)}"))
        root.addView(button("Refresh status") { render() })
        root.addView(text("Watched products", true))
        Store.products(this).forEach { product ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text(product).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(button("Remove") { Store.setProducts(this, Store.products(this).filterNot { it == product }); render() })
            root.addView(row)
        }
        val input = EditText(this).apply { hint = "Product name, e.g. Dreame X60 Pro Ultra"; isSingleLine = true }
        root.addView(input)
        root.addView(button("Add product") {
            val name = input.text.toString().trim()
            if (name.isNotBlank() && name !in Store.products(this)) { Store.setProducts(this, Store.products(this) + name); render() }
        })
        root.addView(text("Matching requires all product-name words in the OCR text. Prices are estimates extracted from the image, not verified offers. For best results, disable battery restrictions for this app."))
    }
}
