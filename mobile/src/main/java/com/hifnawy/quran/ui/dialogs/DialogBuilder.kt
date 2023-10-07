package com.hifnawy.quran.ui.dialogs

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hifnawy.quran.R
import com.hifnawy.quran.databinding.DownloadDialogBinding

class DialogBuilder {

    enum class DownloadType {
        SINGLE, BULK
    }

    companion object {

        fun showErrorDialog(
                context: Context,
                title: String,
                message: String,
                buttonText: String,
                buttonListener: DialogInterface.OnClickListener
        ) {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(buttonText, buttonListener)
                .create()
                .apply {
                    window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    show()
                }
        }

        @SuppressLint("SetTextI18n")
        fun prepareDownloadDialog(
                context: Context,
                downloadType: DownloadType
        ): Pair<AlertDialog, DownloadDialogBinding> {
            val dialogBinding = DownloadDialogBinding.inflate(LayoutInflater.from(context), null, false)

            when (downloadType) {
                DownloadType.SINGLE -> {
                    dialogBinding.downloadDialogAllChaptersProgress.visibility = View.GONE
                    dialogBinding.downloadDialogAllChaptersDownloadMessage.visibility = View.GONE
                }

                DownloadType.BULK -> Unit
            }

            val dialog = MaterialAlertDialogBuilder(context)
                .setView(dialogBinding.root)
                .setCancelable(false)
                .create()

            dialog.window?.decorView?.setBackgroundColor(Color.TRANSPARENT)

            return dialog to dialogBinding
        }
    }
}