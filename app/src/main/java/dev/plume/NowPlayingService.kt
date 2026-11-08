package dev.plume

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself. Android only lets an app read what other apps are playing (MediaSessionManager) once it holds
 * a notification listener, so this is the permission slip for the "now playing" pill in photo-frame mode.
 */
class NowPlayingService : NotificationListenerService()

