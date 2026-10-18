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
        var isConnected: Boolean = false
            private set

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            val myPackage = context.packageName
            return enabledListeners.contains(myPackage)
        }

        fun rebindService(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    val component = android.content.ComponentName(context, CarNotificationListenerService::class.java)
                    if (!isConnected) {
                        Log.i(TAG, "Listener neconectat - fortare rebind prin component toggle...")
                        val pm = context.packageManager
                        pm.setComponentEnabledSetting(
                            component,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                        pm.setComponentEnabledSetting(
                            component,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                    }
                    requestRebind(component)
                    Log.i(TAG, "requestRebind executat pentru CarNotificationListenerService")
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare rebindService: ${e.message}")
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, ">>> CarNotificationListenerService CONECTAT la sistem! <<<")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, ">>> CarNotificationListenerService DECONECTAT! Se incearca reconectarea... <<<")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                rebindService(this)
            } catch (e: Exception) {
                Log.e(TAG, "Eroare requestRebind in onListenerDisconnected: ${e.message}")
            }
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

        // Verificăm dacă este o aplicație de comunicare / mesaje
        val isMessagingApp = isMessageSource(pkg, n)
        Log.d(TAG, "onNotificationPosted: pkg=$pkg, isMessagingApp=$isMessagingApp, category=${n.category}")
        if (!isMessagingApp) return

        val extras = n.extras ?: return

        // 1. Extragere text și expeditor de bază
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        var sender = rawTitle
        var message = rawText
        var hasAudioMime = false

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
                    val mime = lastMsg.dataMimeType
                    if (mime != null && mime.startsWith("audio/")) {
                        hasAudioMime = true
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

        if (sender.isEmpty() && message.isEmpty()) {
            Log.d(TAG, "Ignorare notificare cu sender si message goale: $pkg")
            return
        }

        // Ignorăm texte de rezumat generic (ex: "2 new messages", "3 mesaje noi")
        if (message.matches(Regex("^\\d+\\s+(new messages|mesaje noi|conversations|conversații).*", RegexOption.IGNORE_CASE))) {
            Log.d(TAG, "Ignorare text rezumat generic: $message")
            return
        }

        // Dacă notificarea este marcată ca Group Summary, verificăm dacă este doar un container fără mesaj concret
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            val isCountOnly = message.contains("messages", ignoreCase = true) ||
                    message.contains("mesaje", ignoreCase = true) ||
                    message.isEmpty()
            val hasDirectMessage = extras.containsKey(Notification.EXTRA_MESSAGES) ||
                    extras.containsKey("android.messagingStyleUser")
            if (isCountOnly && !hasDirectMessage) {
                Log.d(TAG, "Ignorare group summary generic: $message pentru $pkg")
                return
            }
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
        val isVoice = hasAudioMime || isVoiceMessage(message, n)

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

        Log.i(TAG, "Notificare receptionata cu succes: App=$appName, De la=$sender, Voice=$isVoice, Msg=$message")
        CarNotificationHub.postNotification(carNotif)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun isMessageSource(pkg: String, n: Notification): Boolean {
        // 1. Daca notificarea are stil MessagingStyle (WhatsApp, SMS, Telegram, Messenger etc.)
        try {
            if (NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null) {
                return true
            }
        } catch (_: Exception) {}

        // 2. Pentru teste adb / shell
        if (pkg == "com.android.shell") return true

        // 3. WhatsApp si WhatsApp Business
        if (pkg.contains("whatsapp", ignoreCase = true)) return true

        // 4. SMS Apps
        if (pkg.contains("messaging", ignoreCase = true) ||
            pkg.contains("mms", ignoreCase = true) ||
            pkg == "com.google.android.apps.messaging" ||
            pkg == "com.samsung.android.messaging") return true

        // 5. Alte aplicatii populare de chat
        if (pkg.contains("telegram", ignoreCase = true) ||
            pkg.contains("securesms", ignoreCase = true) || // Signal
            pkg.contains("viber", ignoreCase = true) ||
            pkg.contains("orca", ignoreCase = true) ||
            pkg.contains("discord", ignoreCase = true) ||
            pkg.contains("skype", ignoreCase = true)) return true

        // 6. Categorie Android directa
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
