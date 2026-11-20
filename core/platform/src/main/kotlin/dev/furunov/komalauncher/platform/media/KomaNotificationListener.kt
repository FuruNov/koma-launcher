package dev.furunov.komalauncher.platform.media

import android.service.notification.NotificationListenerService

/**
 * 「通知へのアクセス」の許可の対象。通知そのものは読まない。
 * MediaSessionManager.getActiveSessions に、このコンポーネントを渡して再生中のアプリを取得する。
 */
class KomaNotificationListener : NotificationListenerService()
