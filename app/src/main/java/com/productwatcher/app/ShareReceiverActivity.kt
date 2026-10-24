package com.productwatcher.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val count = runCatching { TelegramImport.import(this, intent) }.getOrElse {
            Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
            0
        }
        Toast.makeText(this, "Saved Telegram share · $count product matches", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
