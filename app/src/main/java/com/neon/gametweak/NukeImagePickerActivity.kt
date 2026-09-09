package com.neon.gametweak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * NukeImagePickerActivity — Invisible trampoline for selecting screenshots/photos in Live Chat.
 * Operates across Android 11 to 16 without disrupting foreground game sessions.
 */
class NukeImagePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(pickIntent, "Choose a photo or report screenshot"), REQ_PICK_IMAGE)
        } catch (e: Throwable) {
            Log.e("NukeImagePicker", "Failed to start image picker", e)
            NukeToast.error(this, "Could not open gallery: ${e.message}")
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE) {
            if (resultCode == RESULT_OK) {
                val uri: Uri? = data?.data
                if (uri != null) {
                    NukeLiveChatOverlay.getInstance(applicationContext).onImagePicked(uri)
                } else {
                    NukeLiveChatOverlay.getInstance(applicationContext).onImagePickerDismissed()
                }
            } else {
                NukeLiveChatOverlay.getInstance(applicationContext).onImagePickerDismissed()
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        NukeLiveChatOverlay.getInstance(applicationContext).onImagePickerDismissed()
    }

    companion object {
        private const val REQ_PICK_IMAGE = 8802

        fun launch(context: Context) {
            val intent = Intent(context, NukeImagePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            context.startActivity(intent)
        }
    }
}
