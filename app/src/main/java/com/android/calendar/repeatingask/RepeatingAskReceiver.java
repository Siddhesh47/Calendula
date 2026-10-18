package com.android.calendar.repeatingask;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver for notification action buttons (Not Now, Don't Repeat Again).
 */
public class RepeatingAskReceiver extends BroadcastReceiver {
    private static final String TAG = "RepeatingAskReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        long eventId = intent.getLongExtra(RepeatingAskConstants.EXTRA_EVENT_ID, -1L);
        Log.d(TAG, "Received action: " + action + " for event: " + eventId);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            RepeatingAskManager.rescheduleAllPendingAsks(context);
            return;
        }

        if (eventId <= 0) {
            return;
        }

        if (RepeatingAskConstants.ACTION_REPEATING_ASK_ALARM.equals(action)
                || RepeatingAskConstants.ACTION_REPEATING_ASK_COOLDOWN.equals(action)) {
            RepeatingAskModel event = RepeatingAskManager.getRepeatingAskEventById(context, eventId);
            if (event != null) {
                boolean isPending = RepeatingAskManager.isEventRepeatingAskAndPending(
                        context.getContentResolver(), eventId);
                long now = System.currentTimeMillis();
                long effectiveEnd = event.getEffectiveEndMillis();

                if (isPending && (effectiveEnd <= now || (effectiveEnd - now) <= 60000L)) {
                    // Show notification with sound/vibration
                    RepeatingAskManager.showRepeatingAskNotification(context, event.id, event.title, effectiveEnd);
                    // Schedule next cooldown alarm so notifications keep re-sending until user acts!
                    RepeatingAskManager.scheduleCooldownForEvent(context, event.id, event.title);
                } else if (isPending && effectiveEnd > now) {
                    RepeatingAskManager.scheduleAskForEvent(context, event.id, effectiveEnd, event.title);
                }
            }
            return;
        }

        // Cancel notification for interactive actions
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel((int) (eventId % Integer.MAX_VALUE));
        }

        if (RepeatingAskConstants.ACTION_REPEATING_ASK_DISMISS.equals(action)) {
            boolean isPending = RepeatingAskManager.isEventRepeatingAskAndPending(
                    context.getContentResolver(), eventId);
            if (!isPending) {
                return;
            }
            // Dismissed for now; snooze in-app and schedule 10-hour cooldown work & alarm
            RepeatingAskManager.snoozeInAppAsk(context, eventId);
            String title = intent.getStringExtra(RepeatingAskConstants.EXTRA_EVENT_TITLE);
            if (title == null) {
                RepeatingAskModel event = RepeatingAskManager.getRepeatingAskEventById(context, eventId);
                if (event != null) title = event.title;
            }
            RepeatingAskManager.scheduleCooldownForEvent(context, eventId, title);
            Log.d(TAG, "Repeating-ask dismissed for event " + eventId + ". Will re-ask on cooldown.");
        } else if (RepeatingAskConstants.ACTION_REPEATING_ASK_STOP.equals(action)) {
            // Permanently stop repeating for this event
            RepeatingAskModel event = RepeatingAskManager.getRepeatingAskEventById(context, eventId);
            String accountName = event != null ? event.calendarAccountName : null;
            String accountType = event != null ? event.calendarAccountType : null;
            RepeatingAskManager.stopRepeating(context, eventId, accountName, accountType);
            Log.d(TAG, "Stopped repeating for event " + eventId);
        }
    }
}
