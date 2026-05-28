package com.example

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.example.data.ClipDatabase
import com.example.data.ClipItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ClipboardHelperActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use a translucent/transparent style
        window.setBackgroundDrawableResource(android.R.color.transparent)
        
        val actionType = intent?.getStringExtra(EXTRA_ACTION_TYPE)
        val textContent = intent?.getStringExtra(EXTRA_TEXT_CONTENT)
        val pcIp = intent?.getStringExtra(EXTRA_PC_IP)

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        when (actionType) {
            ACTION_COPY -> {
                if (!textContent.isNullOrEmpty()) {
                    val clip = ClipData.newPlainText("Synced Clip", textContent)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(this, "Copied from PC!", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_PASTE_AND_SEND -> {
                val primaryClip = clipboardManager.primaryClip
                if (primaryClip != null && primaryClip.itemCount > 0) {
                    val text = primaryClip.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotEmpty() && !pcIp.isNullOrEmpty()) {
                        sendLocalClipboardToPc(text, pcIp)
                    } else if (text.isEmpty()) {
                        Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        finish()
    }

    private fun sendLocalClipboardToPc(text: String, pcIp: String) {
        val database = ClipDatabase.getDatabase(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Post clipboard to PC
                val client = OkHttpClient()
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val body = text.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("http://$pcIp:40040/sync")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // Persist to local database
                        database.clipDao.insertClip(
                            ClipItem(
                                text = text,
                                isSent = true,
                                peerName = pcIp
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val EXTRA_ACTION_TYPE = "EXTRA_ACTION_TYPE"
        const val EXTRA_TEXT_CONTENT = "EXTRA_TEXT_CONTENT"
        const val EXTRA_PC_IP = "EXTRA_PC_IP"

        const val ACTION_COPY = "ACTION_COPY"
        const val ACTION_PASTE_AND_SEND = "ACTION_PASTE_AND_SEND"

        fun startForCopy(context: Context, text: String) {
            val intent = Intent(context, ClipboardHelperActivity::class.java).apply {
                putExtra(EXTRA_ACTION_TYPE, ACTION_COPY)
                putExtra(EXTRA_TEXT_CONTENT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }

        fun startForPasteAndSend(context: Context, pcIp: String) {
            val intent = Intent(context, ClipboardHelperActivity::class.java).apply {
                putExtra(EXTRA_ACTION_TYPE, ACTION_PASTE_AND_SEND)
                putExtra(EXTRA_PC_IP, pcIp)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
    }
}
