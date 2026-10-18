package com.bmw.carlauncher.notification

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class CarNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "CarNotifService"

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            val myPackage = context.packageName
            return enabledListeners.contains(myPackage)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return

        // Nu afișăm propriile notificări sau notificările permanente/ongoing
        if (pkg == packageName) return
        if (sbn.isOngoing) return

        val n = sbn.notification ?: return

        // Ignorăm notificările de tip Group Summary (ex: "2 new messages", "3 mesaje din 2 conversații")
        // pentru a nu suprascrie mesajul individual primit
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "Ignorare group summary notification pentru $pkg")
            return
        }

        // Verificăm dacă este o aplicație de comunicare / mesaje
        val isMessagingApp = isMessageSource(pkg, n)
        if (!isMessagingApp) return

        val extras = n.extras ?: return

        // 1. Extragere text și expeditor
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        if (rawTitle.isEmpty() && rawText.isEmpty()) return

        // Ignorăm texte de rezumat colectiv
        if (rawText.matches(Regex("^\\d+\\s+(new messages|mesaje noi).*", RegexOption.IGNORE_CASE))) {
            Log.d(TAG, "Ignorare text rezumat generic: $rawText")
            return
        }

        var sender = rawTitle
        var message = rawText

        // Verificare NotificationCompat.MessagingStyle pentru detalii bogate (WhatsApp, SMS etc.)
        try {
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            if (messagingStyle != null) {
                val messages = messagingStyle.messages
                if (messages.isNotEmpty()) {
                    val lastMsg = messages.last()
                    val msgText = lastMsg.text?.toString()?.trim()
                    if (!msgText.isNullOrEmpty()) {
                        message = msgText
                    }
                    val personName = lastMsg.person?.name?.toString()?.trim()
                    if (!personName.isNullOrEmpty()) {
                        sender = personName
                    } else {
                        val convTitle = messagingStyle.conversationTitle?.toString()?.trim()
                        if (!convTitle.isNullOrEmpty()) {
                            sender = convTitle
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eroare parsare MessagingStyle: ${e.message}")
        }

        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "Mesaj"
        }

        if (sender.isEmpty()) {
            sender = appName
        }

        // 2. Detectare Mesaj Vocal (WhatsApp sau alte aplicații de chat)
        val isVoice = isVoiceMessage(message, n)

        val appIcon = try {
            pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }

        // 3. Extragere actiune de redare directa (daca aplicatia a expus un Notification.Action)
        var playAction: android.app.PendingIntent? = null
        val actions = n.actions
        if (actions != null) {
            for (action in actions) {
                val actionTitle = action.title?.toString()?.lowercase() ?: ""
                if (actionTitle.contains("play") ||
                    actionTitle.contains("redare") ||
                    actionTitle.contains("ascult") ||
                    actionTitle.contains("listen")) {
                    playAction = action.actionIntent
                    break
                }
            }
        }

        val carNotif = CarNotification(
            key = sbn.key,
            packageName = pkg,
            appName = appName,
            sender = sender,
            message = message,
            timestamp = sbn.postTime,
            isVoiceMessage = isVoice,
            contentIntent = n.contentIntent,
            playActionIntent = playAction,
            appIcon = appIcon
        )

        Log.d(TAG, "Notificare receptionata: App=$appName, De la=$sender, Voice=$isVoice, Msg=$message")
        CarNotificationHub.postNotification(carNotif)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun isMessageSource(pkg: String, n: Notification): Boolean {
        // WhatsApp si WhatsApp Business
        if (pkg.contains("whatsapp", ignoreCase = true)) return true

        // SMS Apps
        if (pkg.contains("messaging", ignoreCase = true) ||
            pkg.contains("mms", ignoreCase = true) ||
            pkg == "com.google.android.apps.messaging" ||
            pkg == "com.samsung.android.messaging") return true

        // Alte aplicatii populare de chat
        if (pkg.contains("telegram", ignoreCase = true) ||
            pkg.contains("securesms", ignoreCase = true) || // Signal
            pkg.contains("viber", ignoreCase = true) ||
            pkg == "com.facebook.orca") return true

        // Categorie Android directa
        if (n.category == Notification.CATEGORY_MESSAGE) return true

        return false
    }

    private fun isVoiceMessage(text: String, n: Notification): Boolean {
        val lower = text.lowercase()
        if (lower.contains("mesaj vocal") ||
            lower.contains("voice message") ||
            lower.contains("audio message") ||
            lower.contains("mesaj audio") ||
            lower.startsWith("🎤") ||
            lower.contains("🎤")) {
            return true
        }
        return false
    }
}
