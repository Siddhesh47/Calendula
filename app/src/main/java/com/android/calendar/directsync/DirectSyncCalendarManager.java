package com.android.calendar.directsync;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.text.TextUtils;
import android.util.Log;

import com.android.calendar.Utils;
import com.android.calendar.persistence.CalendarRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages reading, writing, and merging events for the Shared Calendar using Android's CalendarContract.
 */
public class DirectSyncCalendarManager {
    private static final String TAG = "DirectSyncCalendar";

    public static final String SHARED_ACCOUNT_NAME = "Shared Calendar (Direct)";
    public static final String SHARED_CALENDAR_DISPLAY_NAME = "Shared Calendar";
    public static final int SHARED_CALENDAR_COLOR = Color.parseColor("#00897B"); // Teal

    private static final String[] CALENDAR_PROJECTION = new String[] {
            Calendars._ID,                  // 0
            Calendars.ACCOUNT_NAME,         // 1
            Calendars.CALENDAR_DISPLAY_NAME // 2
    };

    private static final String[] EVENT_PROJECTION = new String[] {
            Events._ID,                     // 0
            Events.TITLE,                   // 1
            Events.DESCRIPTION,             // 2
            Events.EVENT_LOCATION,          // 3
            Events.DTSTART,                 // 4
            Events.DTEND,                   // 5
            Events.ALL_DAY,                 // 6
            Events.EVENT_TIMEZONE,          // 7
            Events.RRULE,                   // 8
            Events.SYNC_DATA1,              // 9 (used for UID)
            Events.SYNC_DATA2,              // 10 (used for lastModified)
            Events.DELETED                  // 11
    };

    /**
     * Gets or creates the dedicated Shared Calendar.
     * @return calendar ID, or -1 if unable to retrieve/create.
     */
    public static synchronized long getOrCreateSharedCalendar(Context context) {
        if (context == null) return -1;
        ContentResolver resolver = context.getContentResolver();

        // 1. Try to query existing shared calendar
        String selection = Calendars.ACCOUNT_NAME + "=? AND " + Calendars.ACCOUNT_TYPE + "=?";
        String[] selectionArgs = new String[] { SHARED_ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL };

        try (Cursor cursor = resolver.query(Calendars.CONTENT_URI, CALENDAR_PROJECTION, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying shared calendar", e);
        }

        // 2. Create the shared calendar
        try {
            ContentValues cv = new ContentValues();
            cv.put(Calendars.ACCOUNT_NAME, SHARED_ACCOUNT_NAME);
            cv.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
            cv.put(Calendars.OWNER_ACCOUNT, SHARED_ACCOUNT_NAME);
            cv.put(Calendars.NAME, "direct_shared_calendar");
            cv.put(Calendars.CALENDAR_DISPLAY_NAME, SHARED_CALENDAR_DISPLAY_NAME);
            cv.put(Calendars.CALENDAR_COLOR, SHARED_CALENDAR_COLOR);
            cv.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_ROOT);
            cv.put(Calendars.VISIBLE, 1);
            cv.put(Calendars.SYNC_EVENTS, 1);
            cv.put(Calendars.CAN_MODIFY_TIME_ZONE, 1);
            cv.put(Calendars.CAN_ORGANIZER_RESPOND, 0);

            Uri insertUri = CalendarRepository.asLocalCalendarSyncAdapter(
                    SHARED_ACCOUNT_NAME, Calendars.CONTENT_URI);
            Uri result = resolver.insert(insertUri, cv);
            if (result != null) {
                long newId = ContentUris.parseId(result);
                Log.d(TAG, "Created Shared Calendar with ID: " + newId);
                return newId;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating shared calendar", e);
        }

        return -1;
    }

    /**
     * Reads all events from the shared calendar.
     */
    public static List<DirectSyncEvent> getSharedEvents(Context context) {
        List<DirectSyncEvent> events = new ArrayList<>();
        long calendarId = getOrCreateSharedCalendar(context);
        if (calendarId <= 0) return events;

        ContentResolver resolver = context.getContentResolver();
        String selection = Events.CALENDAR_ID + "=? AND " + Events.DELETED + "=0";
        String[] selectionArgs = new String[] { Long.toString(calendarId) };

        try (Cursor cursor = resolver.query(Events.CONTENT_URI, EVENT_PROJECTION, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String title = cursor.getString(1);
                    String description = cursor.getString(2);
                    String location = cursor.getString(3);
                    long start = cursor.getLong(4);
                    long end = cursor.getLong(5);
                    boolean allDay = cursor.getInt(6) == 1;
                    String timezone = cursor.getString(7);
                    String rrule = cursor.getString(8);
                    String uid = cursor.getString(9);
                    String lastModifiedStr = cursor.getString(10);

                    // Ensure every event has a persistent UID
                    if (TextUtils.isEmpty(uid)) {
                        uid = UUID.randomUUID().toString();
                        ContentValues updateCv = new ContentValues();
                        updateCv.put(Events.SYNC_DATA1, uid);
                        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), updateCv, null, null);
                    }

                    long lastModified = 0;
                    if (!TextUtils.isEmpty(lastModifiedStr)) {
                        try {
                            lastModified = Long.parseLong(lastModifiedStr);
                        } catch (Exception ignored) {}
                    }
                    if (lastModified <= 0) {
                        lastModified = System.currentTimeMillis();
                    }

                    DirectSyncEvent event = new DirectSyncEvent();
                    event.uid = uid;
                    event.title = title != null ? title : "";
                    event.description = description != null ? description : "";
                    event.location = location != null ? location : "";
                    event.startMillis = start;
                    event.endMillis = end;
                    event.allDay = allDay;
                    event.timezone = !TextUtils.isEmpty(timezone) ? timezone : Utils.getTimeZone(context, null);
                    event.rrule = rrule;
                    event.lastModified = lastModified;
                    event.isDeleted = false;

                    events.add(event);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying shared calendar events", e);
        }

        return events;
    }

    /**
     * Merges incoming events from a peer device into the local Shared Calendar.
     * Uses UID and lastModified for conflict resolution.
     * @return Number of events added or updated.
     */
    public static int mergeIncomingEvents(Context context, List<DirectSyncEvent> incomingEvents) {
        if (context == null || incomingEvents == null || incomingEvents.isEmpty()) {
            return 0;
        }

        long calendarId = getOrCreateSharedCalendar(context);
        if (calendarId <= 0) return 0;

        ContentResolver resolver = context.getContentResolver();
        int changeCount = 0;

        for (DirectSyncEvent incoming : incomingEvents) {
            if (incoming == null || TextUtils.isEmpty(incoming.uid)) continue;

            String selection = Events.CALENDAR_ID + "=? AND " + Events.SYNC_DATA1 + "=?";
            String[] selectionArgs = new String[] { Long.toString(calendarId), incoming.uid };

            long existingId = -1;
            long existingLastModified = 0;

            try (Cursor cursor = resolver.query(Events.CONTENT_URI, new String[] { Events._ID, Events.SYNC_DATA2 }, selection, selectionArgs, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    existingId = cursor.getLong(0);
                    String modStr = cursor.getString(1);
                    if (!TextUtils.isEmpty(modStr)) {
                        try {
                            existingLastModified = Long.parseLong(modStr);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking existing event: " + incoming.uid, e);
            }

            if (existingId == -1) {
                // Not found locally -> insert if not deleted
                if (!incoming.isDeleted) {
                    ContentValues cv = buildContentValues(calendarId, incoming);
                    Uri inserted = resolver.insert(Events.CONTENT_URI, cv);
                    if (inserted != null) {
                        changeCount++;
                    }
                }
            } else {
                // Found locally -> check timestamps
                if (incoming.lastModified >= existingLastModified) {
                    if (incoming.isDeleted) {
                        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, existingId), null, null);
                        changeCount++;
                    } else {
                        ContentValues cv = buildContentValues(calendarId, incoming);
                        int updated = resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, existingId), cv, null, null);
                        if (updated > 0) {
                            changeCount++;
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Merged incoming events. Changes applied: " + changeCount);
        return changeCount;
    }

    /**
     * Gets the count of events in the shared calendar.
     */
    public static int getSharedEventCount(Context context) {
        long calendarId = getOrCreateSharedCalendar(context);
        if (calendarId <= 0) return 0;

        ContentResolver resolver = context.getContentResolver();
        String selection = Events.CALENDAR_ID + "=? AND " + Events.DELETED + "=0";
        String[] selectionArgs = new String[] { Long.toString(calendarId) };

        try (Cursor cursor = resolver.query(Events.CONTENT_URI, new String[] { Events._ID }, selection, selectionArgs, null)) {
            if (cursor != null) {
                return cursor.getCount();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error counting events", e);
        }
        return 0;
    }

    private static ContentValues buildContentValues(long calendarId, DirectSyncEvent event) {
        ContentValues cv = new ContentValues();
        cv.put(Events.CALENDAR_ID, calendarId);
        cv.put(Events.TITLE, event.title);
        cv.put(Events.DESCRIPTION, event.description);
        cv.put(Events.EVENT_LOCATION, event.location);
        cv.put(Events.DTSTART, event.startMillis);
        cv.put(Events.DTEND, event.endMillis);
        cv.put(Events.ALL_DAY, event.allDay ? 1 : 0);
        cv.put(Events.EVENT_TIMEZONE, !TextUtils.isEmpty(event.timezone) ? event.timezone : "UTC");
        cv.put(Events.RRULE, event.rrule);
        cv.put(Events.SYNC_DATA1, event.uid);
        cv.put(Events.SYNC_DATA2, Long.toString(event.lastModified));
        cv.put(Events.STATUS, Events.STATUS_CONFIRMED);
        cv.put(Events.HAS_ATTENDEE_DATA, 0);
        return cv;
    }
}
