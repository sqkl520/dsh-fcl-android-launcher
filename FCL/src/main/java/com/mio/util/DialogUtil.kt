package com.mio.util

import android.content.Context
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.dialog.FCLDialog

fun showErrorDialog(context: Context, message: Int, vararg args: String?) {
    showErrorDialog(context, context.getString(message, *args))
}

fun showErrorDialog(context: Context, message: String) {
    FCLAlertDialog.Builder(context)
        .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
        .setMessage(message)
        .setNegativeButton(context.getString(R.string.dialog_positive)) { }
        .create()
        .show()
}

fun showWarningDialog(context: Context, message: String, onConfirm: () -> Unit) {
    FCLAlertDialog.Builder(context)
        .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
        .setMessage(message)
        .setPositiveButton {
            onConfirm()
        }
        .setNegativeButton {

        }
        .create()
        .show()
}