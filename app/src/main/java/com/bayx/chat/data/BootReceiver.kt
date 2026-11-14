package com.bayx.chat.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon yeniden başladığında, oturum açıksa arka plan servisini yeniden başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Bayx.auth.value is AuthState.LoggedOut) return
        SyncService.start(context.applicationContext)
    }
}
