package com.android.calendar.event;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.ExtendedProperties;
import android.provider.CalendarContract.Calendars;

public class ExtendedProperty {
    /**
     * The name to be used with the extended property.
     * @see <a href="https://developer.android.com/reference/kotlin/android/provider/CalendarContract.ExtendedProperties">CalendarContact.ExtendedProperties</a>
     */
    public static final String URL_NAME = ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd.ical4android.url";

    public static final String URL_NAME_PRIV = "private:" + URL_NAME;

    /**
     * A short utility identifier for the URL extended field. Works as an equivalent of
     * `CalendarContract.Events.TITLE` for the URL field.
     */
    public static final String URL = "url";

    /**
     * Extended property name for marking an event as a Repeating-Ask event.
     */
    public static final String REPEATING_ASK_NAME = ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd.ws.xsoh.etar.repeating_ask";
    public static final String REPEATING_ASK_NAME_PRIV = "private:" + REPEATING_ASK_NAME;
    public static final String REPEATING_ASK_KEY = "repeating_ask";

    /**
     * Extended property name for marking that an ended repeating-ask event has been answered or rescheduled.
     */
    public static final String REPEATING_ASK_ANSWERED_NAME = ContentResolver.CURSOR_ITEM_BASE_TYPE + "/vnd.ws.xsoh.etar.repeating_ask_answered";
    public static final String REPEATING_ASK_ANSWERED_NAME_PRIV = "private:" + REPEATING_ASK_ANSWERED_NAME;

    /**
     * Gets the Content URI for Extended Properties after adding the account name and type, and
     * setting the `CalendarContract.CALLER_IS_SYNCADAPTER` parameter to `true`.
     * @param accountName The name of the account owner of the extended property.
     * @param accountType The type of the account owner of the extended property.
     */
    public static Uri contentUri(String accountName, String accountType) {
        Uri.Builder builder = ExtendedProperties.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true");
        if (accountName != null) {
            builder.appendQueryParameter(Calendars.ACCOUNT_NAME, accountName);
        }
        if (accountType != null) {
            builder.appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType);
        }
        return builder.build();
    }
}
