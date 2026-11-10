package com.crewpocket.helper;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * User-initiated, one-tap route to the existing Accessibility disclosure.
 * Never starts an Activity from the background or grants a permission itself.
 * Notification delivery may be blocked by Android notification permissions;
 * the Live tool response always supplies the spoken fallback instructions.
 */
final class ScreenActionCapabilityNotice {
    private static final String CHANNEL = "crew_screen_actions";
    private static final int NOTIFICATION_ID = 87665;

    private ScreenActionCapabilityNotice() {}

    static void show(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            NotificationManager manager = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                Class<?> channelClass =
                        Class.forName("android.app.NotificationChannel");
                Object channel = channelClass
                        .getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL,
                                I18n.get(app, "螢幕操作", "Screen actions"),
                                NotificationManager.IMPORTANCE_DEFAULT);
                NotificationManager.class
                        .getMethod("createNotificationChannel", channelClass)
                        .invoke(manager, channel);
            }

            Intent intent = new Intent(app, MainActivity.class);
            intent.putExtra(
                    MainActivity.EXTRA_OPEN_SCREEN_ACTIONS_SETTINGS, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent action = PendingIntent.getActivity(
                    app, NOTIFICATION_ID, intent, flags);

            Notification.Builder builder = new Notification.Builder(app)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(I18n.get(app,
                            "Crew Helper：螢幕操作未啟用",
                            "Crew Helper: Screen actions disabled"))
                    .setContentText(I18n.get(app,
                            "點此啟用，才能開啟 App 與操作畫面",
                            "Tap to enable app launches and screen actions"))
                    .setContentIntent(action)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false);
            if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(CHANNEL);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception | LinkageError ignored) {
            // POST_NOTIFICATIONS denial and vendor restrictions are not
            // grounds to retry the blocked phone mutation.
        }
    }

    static void clear(Context context) {
        if (context == null) return;
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
    }
}
