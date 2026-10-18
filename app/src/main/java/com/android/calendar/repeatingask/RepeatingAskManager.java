package com.android.calendar.repeatingask;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.ExtendedProperties;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.android.calendar.AllInOneActivity;
import com.android.calendar.Utils;
import com.android.calendar.event.ExtendedProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import ws.xsoh.etar.R;

/**
 * Manages scheduling, notifications, and event creation for the Repeating-Ask feature.
 */
public class RepeatingAskManager {
    private static final String TAG = "RepeatingAskManager";

    private static final String[] EVENT_PROJECTION = new String[] {
            Events._ID,                  // 0
            Events.TITLE,                // 1
            Events.DTSTART,              // 2
            Events.DTEND,                // 3
            Events.ALL_DAY,              // 4
            Events.CALENDAR_ID,          // 5
            Events.EVENT_LOCATION,       // 6
            Events.DESCRIPTION,          // 7
            Events.EVENT_TIMEZONE,       // 8
            Events.ACCOUNT_NAME,         // 9
            Events.ACCOUNT_TYPE          // 10
    };

    private static final String[] EXTENDED_PROJECTION = new String[] {
            ExtendedProperties._ID,      // 0
            ExtendedProperties.EVENT_ID, // 1
            ExtendedProperties.NAME,     // 2
            ExtendedProperties.VALUE     // 3
    };

    /**
     * Schedules an alarm with AlarmManager to guarantee exact firing even in Doze mode
     * and for long-term delays.
     */
    private static void scheduleAlarm(Context context, long eventId, long triggerAtMillis, String action, String title) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, RepeatingAskReceiver.class);
        intent.setAction(action);
        intent.putExtra(RepeatingAskConstants.EXTRA_EVENT_ID, eventId);
        intent.putExtra(RepeatingAskConstants.EXTRA_EVENT_TITLE, title);
        intent.putExtra(RepeatingAskConstants.EXTRA_EVENT_END, triggerAtMillis);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        int requestCode = getAlarmRequestCode(eventId, action);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags);

        long trigger = Math.max(System.currentTimeMillis(), triggerAtMillis);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
            Log.d(TAG, "Scheduled AlarmManager for action " + action + " on event " + eventId + " at " + trigger);
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException scheduling exact alarm, falling back to set", se);
            try {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
            } catch (Exception ignored) {}
        }
    }

    private static void cancelAlarm(Context context, long eventId, String action) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, RepeatingAskReceiver.class);
        intent.setAction(action);
        intent.putExtra(RepeatingAskConstants.EXTRA_EVENT_ID, eventId);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        int requestCode = getAlarmRequestCode(eventId, action);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags);
        am.cancel(pi);
    }

    private static int getAlarmRequestCode(long eventId, String action) {
        int base = (int) (eventId % 10000000);
        if (RepeatingAskConstants.ACTION_REPEATING_ASK_COOLDOWN.equals(action)) {
            return base * 10 + 1;
        }
        return base * 10;
    }

    /**
     * Schedules the initial ask for an event right after its end time.
     * Uses AlarmManager for exact waking (essential for long-term events and Doze mode)
     * and WorkManager as secondary redundancy.
     */
    public static void scheduleAskForEvent(Context context, long eventId, long endMillis, String title) {
        if (context == null || eventId <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long delayMillis = Math.max(0L, endMillis - now);

        // 1. Exact AlarmManager schedule for long-term reliability
        scheduleAlarm(context, eventId, endMillis, RepeatingAskConstants.ACTION_REPEATING_ASK_ALARM, title);

        // 2. WorkManager request as secondary redundancy
        Data inputData = new Data.Builder()
                .putLong(RepeatingAskConstants.KEY_EVENT_ID, eventId)
                .putString(RepeatingAskConstants.KEY_EVENT_TITLE, title)
                .build();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(RepeatingAskWorker.class)
                .setInputData(inputData)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(RepeatingAskConstants.WORK_NAME_PREFIX + eventId)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                RepeatingAskConstants.WORK_NAME_PREFIX + eventId,
                ExistingWorkPolicy.REPLACE,
                workRequest);

        Log.d(TAG, "Scheduled repeating-ask for event " + eventId + " at endMillis=" + endMillis + " (in " + delayMillis + " ms)");
    }

    /**
     * Schedules a repeated ask after the 10-hour cooldown if the user didn't respond.
     * Uses AlarmManager and WorkManager so it keeps re-sending reliably until the user acts.
     */
    public static void scheduleCooldownForEvent(Context context, long eventId, String title) {
        if (context == null || eventId <= 0) {
            return;
        }

        long delayMillis = TimeUnit.HOURS.toMillis(RepeatingAskConstants.REPEATING_ASK_COOLDOWN_HOURS);
        long triggerAtMillis = System.currentTimeMillis() + delayMillis;

        // 1. Exact AlarmManager schedule
        scheduleAlarm(context, eventId, triggerAtMillis, RepeatingAskConstants.ACTION_REPEATING_ASK_COOLDOWN, title);

        // 2. WorkManager schedule
        Data inputData = new Data.Builder()
                .putLong(RepeatingAskConstants.KEY_EVENT_ID, eventId)
                .putString(RepeatingAskConstants.KEY_EVENT_TITLE, title)
                .build();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(RepeatingAskWorker.class)
                .setInputData(inputData)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(RepeatingAskConstants.WORK_NAME_PREFIX + eventId)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                RepeatingAskConstants.WORK_NAME_PREFIX + eventId,
                ExistingWorkPolicy.REPLACE,
                workRequest);

        Log.d(TAG, "Scheduled 10-hour cooldown for event " + eventId + " at " + triggerAtMillis);
    }

    private static final String PREFS_REPEATING_ASK = "repeating_ask_prefs";
    private static final String PREF_SNOOZE_PREFIX = "snooze_until_";

    /**
     * Records a snooze timestamp for the 10-hour cooldown so in-app popups don't nag repeatedly.
     */
    public static void snoozeInAppAsk(Context context, long eventId) {
        if (context == null || eventId <= 0) return;
        long snoozeUntil = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(RepeatingAskConstants.REPEATING_ASK_COOLDOWN_HOURS);
        context.getSharedPreferences(PREFS_REPEATING_ASK, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_SNOOZE_PREFIX + eventId, snoozeUntil)
                .apply();
    }

    /**
     * Checks if the in-app popup for this event is currently snoozed under the 10-hour cooldown.
     */
    public static boolean isEventSnoozed(Context context, long eventId) {
        if (context == null || eventId <= 0) return false;
        long snoozeUntil = context.getSharedPreferences(PREFS_REPEATING_ASK, Context.MODE_PRIVATE)
                .getLong(PREF_SNOOZE_PREFIX + eventId, 0L);
        return System.currentTimeMillis() < snoozeUntil;
    }

    /**
     * Cancels any pending repeating-ask work, alarms, and notification for this event.
     */
    public static void cancelAskForEvent(Context context, long eventId) {
        if (context == null || eventId <= 0) {
            return;
        }

        cancelAlarm(context, eventId, RepeatingAskConstants.ACTION_REPEATING_ASK_ALARM);
        cancelAlarm(context, eventId, RepeatingAskConstants.ACTION_REPEATING_ASK_COOLDOWN);
        WorkManager.getInstance(context).cancelUniqueWork(RepeatingAskConstants.WORK_NAME_PREFIX + eventId);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(getNotificationId(eventId));
        }

        context.getSharedPreferences(PREFS_REPEATING_ASK, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_SNOOZE_PREFIX + eventId)
                .apply();

        Log.d(TAG, "Cancelled repeating-ask alarms, work, and notifications for event " + eventId);
    }

    /**
     * Creates the dedicated notification channel for repeating-ask reminders.
     */
    public static void createNotificationChannel(Context context) {
        if (Utils.isOreoOrLater()) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        RepeatingAskConstants.NOTIFICATION_CHANNEL_ID,
                        context.getString(R.string.repeating_ask_notification_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription(context.getString(R.string.repeating_ask_notification_channel_desc));
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Displays a system notification asking the user for the next date & time.
     * Guaranteed to re-alert with sound/vibration on each cooldown cycle until the user acts.
     */
    public static void showRepeatingAskNotification(Context context, long eventId, String title, long endMillis) {
        if (context == null || eventId <= 0) {
            return;
        }

        int requestCode = getNotificationId(eventId);
        NotificationManagerCompat nmc = NotificationManagerCompat.from(context);
        // Cancel first to clear any stale state so Android alerts fresh
        nmc.cancel(requestCode);

        createNotificationChannel(context);

        String displayTitle = !TextUtils.isEmpty(title) ? title : context.getString(R.string.no_title_label);
        String notifTitle = context.getString(R.string.repeating_ask_notification_title, displayTitle);
        String notifText = context.getString(R.string.repeating_ask_notification_text);

        // Content intent: Opens AllInOneActivity with the pick dialog
        Intent pickIntent = new Intent(context, AllInOneActivity.class);
        pickIntent.setAction(RepeatingAskConstants.ACTION_REPEATING_ASK_PICK);
        pickIntent.putExtra(RepeatingAskConstants.EXTRA_EVENT_ID, eventId);
        pickIntent.putExtra(RepeatingAskConstants.EXTRA_EVENT_TITLE, displayTitle);
        pickIntent.putExtra(RepeatingAskConstants.EXTRA_EVENT_END, endMillis);
        pickIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent contentPendingIntent = PendingIntent.getActivity(context, requestCode, pickIntent, pendingFlags);

        // Action: Pick Date & Time
        NotificationCompat.Action pickAction = new NotificationCompat.Action.Builder(
                R.drawable.stat_notify_calendar_events,
                context.getString(R.string.repeating_ask_pick_date_time),
                contentPendingIntent
        ).build();

        // Action: Not Now (Dismiss notification for now, cooldown triggers in 10h)
        Intent dismissIntent = new Intent(context, RepeatingAskReceiver.class);
        dismissIntent.setAction(RepeatingAskConstants.ACTION_REPEATING_ASK_DISMISS);
        dismissIntent.putExtra(RepeatingAskConstants.EXTRA_EVENT_ID, eventId);
        dismissIntent.putExtra(RepeatingAskConstants.EXTRA_EVENT_TITLE, displayTitle);
        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(context, requestCode + 1, dismissIntent, pendingFlags);

        NotificationCompat.Action dismissAction = new NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.repeating_ask_dismiss),
                dismissPendingIntent
        ).build();

        // Action: Don't repeat again
        Intent stopIntent = new Intent(context, RepeatingAskReceiver.class);
        stopIntent.setAction(RepeatingAskConstants.ACTION_REPEATING_ASK_STOP);
        stopIntent.putExtra(RepeatingAskConstants.EXTRA_EVENT_ID, eventId);
        stopIntent.putExtra(RepeatingAskConstants.EXTRA_EVENT_TITLE, displayTitle);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(context, requestCode + 2, stopIntent, pendingFlags);

        NotificationCompat.Action stopAction = new NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.repeating_ask_stop_repeating),
                stopPendingIntent
        ).build();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, RepeatingAskConstants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(notifTitle)
                .setContentText(notifText)
                .setSmallIcon(R.drawable.stat_notify_calendar_events)
                .setContentIntent(contentPendingIntent)
                .setDeleteIntent(dismissPendingIntent) // When swiped away, treat as dismiss and keep cooldown
                .setAutoCancel(true)
                .setOnlyAlertOnce(false) // Ensures each re-ask alerts with sound/vibrate
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .addAction(pickAction)
                .addAction(dismissAction)
                .addAction(stopAction);

        nmc.notify(requestCode, builder.build());
        Log.d(TAG, "Displayed repeating-ask notification for event " + eventId);
    }

    /**
     * Queries all active (non-answered) repeating-ask events regardless of when they end.
     */
    public static List<RepeatingAskModel> getAllActiveRepeatingAskEvents(Context context) {
        List<RepeatingAskModel> activeList = new ArrayList<>();
        if (context == null) {
            return activeList;
        }

        ContentResolver resolver = context.getContentResolver();
        String selection = "(" + ExtendedProperties.NAME + " = ? OR " + ExtendedProperties.NAME + " = ?) AND "
                + ExtendedProperties.VALUE + " = ?";
        String[] selectionArgs = new String[] {
                ExtendedProperty.REPEATING_ASK_NAME,
                ExtendedProperty.REPEATING_ASK_NAME_PRIV,
                "true"
        };

        Set<Long> candidateIds = new LinkedHashSet<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(ExtendedProperties.CONTENT_URI,
                    new String[] { ExtendedProperties.EVENT_ID },
                    selection, selectionArgs, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    if (id > 0) {
                        candidateIds.add(id);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying repeating-ask extended properties", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        for (Long eventId : candidateIds) {
            if (isEventRepeatingAskAndPending(resolver, eventId)) {
                RepeatingAskModel model = getRepeatingAskEventById(context, eventId);
                if (model != null) {
                    activeList.add(model);
                }
            }
        }

        return activeList;
    }

    /**
     * Queries all ended events that have the Repeating-Ask flag enabled and have not yet been answered.
     * Correctly handles all-day events and events with DURATION instead of DTEND.
     */
    public static List<RepeatingAskModel> getPendingRepeatingAskEvents(Context context) {
        List<RepeatingAskModel> pendingList = new ArrayList<>();
        if (context == null) {
            return pendingList;
        }

        long now = System.currentTimeMillis();
        List<RepeatingAskModel> activeEvents = getAllActiveRepeatingAskEvents(context);
        for (RepeatingAskModel model : activeEvents) {
            if (model.getEffectiveEndMillis() <= now) {
                pendingList.add(model);
            }
        }

        return pendingList;
    }

    /**
     * Audits and reschedules all active repeating-ask events on boot, time changes, or app resume.
     * For future events: schedules exact AlarmManager alarms.
     * For ended events: triggers notifications (if not snoozed) and keeps cooldown active.
     */
    public static void rescheduleAllPendingAsks(Context context) {
        if (context == null) return;
        List<RepeatingAskModel> activeEvents = getAllActiveRepeatingAskEvents(context);
        long now = System.currentTimeMillis();
        for (RepeatingAskModel model : activeEvents) {
            long effectiveEnd = model.getEffectiveEndMillis();
            if (effectiveEnd <= now) {
                if (!isEventSnoozed(context, model.id)) {
                    showRepeatingAskNotification(context, model.id, model.title, effectiveEnd);
                }
                scheduleCooldownForEvent(context, model.id, model.title);
            } else {
                scheduleAskForEvent(context, model.id, effectiveEnd, model.title);
            }
        }
    }

    /**
     * Checks if a specific event has the repeating-ask flag set and is not yet marked answered.
     */
    public static boolean isEventRepeatingAskAndPending(ContentResolver resolver, long eventId) {
        String selection = ExtendedProperties.EVENT_ID + " = ?";
        String[] selectionArgs = new String[] { Long.toString(eventId) };

        boolean isRepeatingAsk = false;
        boolean isAnswered = false;

        Cursor cursor = null;
        try {
            cursor = resolver.query(ExtendedProperties.CONTENT_URI, EXTENDED_PROJECTION, selection, selectionArgs, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(2);
                    String value = cursor.getString(3);
                    if (ExtendedProperty.REPEATING_ASK_NAME.equals(name) ||
                            ExtendedProperty.REPEATING_ASK_NAME_PRIV.equals(name)) {
                        isRepeatingAsk = "true".equalsIgnoreCase(value);
                    } else if (ExtendedProperty.REPEATING_ASK_ANSWERED_NAME.equals(name) ||
                            ExtendedProperty.REPEATING_ASK_ANSWERED_NAME_PRIV.equals(name)) {
                        isAnswered = "true".equalsIgnoreCase(value);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking extended properties for event " + eventId, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return isRepeatingAsk && !isAnswered;
    }

    /**
     * Fetches details for a single repeating-ask event by its ID.
     */
    public static RepeatingAskModel getRepeatingAskEventById(Context context, long eventId) {
        if (context == null || eventId <= 0) {
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        Uri uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, EVENT_PROJECTION, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return buildModelFromCursor(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching event by id: " + eventId, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * Marks an event as answered/rescheduled so it won't trigger further asks.
     */
    public static void markEventAnswered(Context context, long eventId, String accountName, String accountType) {
        if (context == null || eventId <= 0) {
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        Uri extUri = ExtendedProperty.contentUri(accountName, accountType);

        ContentValues values = new ContentValues();
        values.put(ExtendedProperties.EVENT_ID, eventId);
        values.put(ExtendedProperties.NAME, ExtendedProperty.REPEATING_ASK_ANSWERED_NAME);
        values.put(ExtendedProperties.VALUE, "true");

        try {
            resolver.insert(extUri, values);
        } catch (Exception e) {
            Log.e(TAG, "Error marking event answered: " + eventId, e);
        }

        cancelAskForEvent(context, eventId);
    }

    /**
     * Removes the repeating-ask flag and marks the event answered.
     */
    public static void stopRepeating(Context context, long eventId, String accountName, String accountType) {
        if (context == null || eventId <= 0) {
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        Uri extUri = ExtendedProperty.contentUri(accountName, accountType);

        try {
            // Delete repeating-ask flag
            String where = ExtendedProperties.EVENT_ID + " = ? AND " + ExtendedProperties.NAME + " = ?";
            resolver.delete(extUri, where, new String[] { Long.toString(eventId), ExtendedProperty.REPEATING_ASK_NAME });
            resolver.delete(extUri, where, new String[] { Long.toString(eventId), ExtendedProperty.REPEATING_ASK_NAME_PRIV });

            // Mark as answered
            markEventAnswered(context, eventId, accountName, accountType);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping repeating for event " + eventId, e);
        }

        cancelAskForEvent(context, eventId);
    }

    /**
     * Creates a new event occurrence for the user-selected date and time,
     * writes it back to CalendarContract, sets repeating-ask on the new event,
     * and schedules the ask-cycle for the new event instance's end.
     */
    public static Uri createNextOccurrence(Context context, RepeatingAskModel originalEvent,
                                           long newStartMillis, long newEndMillis) {
        if (context == null || originalEvent == null) {
            return null;
        }

        ContentResolver resolver = context.getContentResolver();

        if (originalEvent.calendarId <= 0 && originalEvent.id > 0) {
            RepeatingAskModel dbEvent = getRepeatingAskEventById(context, originalEvent.id);
            if (dbEvent != null && dbEvent.calendarId > 0) {
                originalEvent.calendarId = dbEvent.calendarId;
                if (TextUtils.isEmpty(originalEvent.calendarAccountName)) {
                    originalEvent.calendarAccountName = dbEvent.calendarAccountName;
                    originalEvent.calendarAccountType = dbEvent.calendarAccountType;
                }
            }
        }

        ContentValues values = new ContentValues();
        values.put(Events.CALENDAR_ID, originalEvent.calendarId);
        values.put(Events.TITLE, originalEvent.title);
        values.put(Events.DESCRIPTION, originalEvent.description);
        values.put(Events.EVENT_LOCATION, originalEvent.location);
        values.put(Events.DTSTART, newStartMillis);
        values.put(Events.DTEND, newEndMillis);
        values.put(Events.ALL_DAY, originalEvent.allDay ? 1 : 0);
        values.put(Events.HAS_ATTENDEE_DATA, 1);
        values.put(Events.STATUS, Events.STATUS_CONFIRMED);
        if (originalEvent.allDay) {
            values.put(Events.EVENT_TIMEZONE, com.android.calendar.calendarcommon2.Time.TIMEZONE_UTC);
        } else {
            values.put(Events.EVENT_TIMEZONE, !TextUtils.isEmpty(originalEvent.timezone)
                    ? originalEvent.timezone : Utils.getTimeZone(context, null));
        }

        Uri newEventUri = null;
        try {
            newEventUri = resolver.insert(Events.CONTENT_URI, values);
            if (newEventUri != null) {
                long newEventId = ContentUris.parseId(newEventUri);
                Uri extUri = ExtendedProperty.contentUri(
                        originalEvent.calendarAccountName, originalEvent.calendarAccountType);

                // Add Repeating-Ask extended property to the new event
                ContentValues extValues = new ContentValues();
                extValues.put(ExtendedProperties.EVENT_ID, newEventId);
                extValues.put(ExtendedProperties.NAME, ExtendedProperty.REPEATING_ASK_NAME);
                extValues.put(ExtendedProperties.VALUE, "true");
                resolver.insert(extUri, extValues);

                // Mark original event as answered
                markEventAnswered(context, originalEvent.id,
                        originalEvent.calendarAccountName, originalEvent.calendarAccountType);

                // Schedule ask cycle for the new event's effective end time
                long effectiveNewEnd = originalEvent.allDay
                        ? Utils.convertAlldayUtcToLocal(null, newEndMillis, Utils.getCurrentTimezone())
                        : newEndMillis;
                scheduleAskForEvent(context, newEventId, effectiveNewEnd, originalEvent.title);

                Log.d(TAG, "Created next occurrence for repeating-ask event: new id=" + newEventId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating next occurrence", e);
        }

        return newEventUri;
    }

    private static RepeatingAskModel buildModelFromCursor(Cursor cursor) {
        RepeatingAskModel model = new RepeatingAskModel();
        model.id = cursor.getLong(0);
        model.title = cursor.getString(1);
        model.startMillis = cursor.getLong(2);
        model.endMillis = cursor.getLong(3);
        model.allDay = cursor.getInt(4) != 0;
        model.calendarId = cursor.getLong(5);
        model.location = cursor.getString(6);
        model.description = cursor.getString(7);
        model.timezone = cursor.getString(8);
        model.calendarAccountName = cursor.getString(9);
        model.calendarAccountType = cursor.getString(10);
        return model;
    }

    private static int getNotificationId(long eventId) {
        return (int) (eventId % Integer.MAX_VALUE);
    }
}
