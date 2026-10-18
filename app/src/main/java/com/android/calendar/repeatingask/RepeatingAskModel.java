package com.android.calendar.repeatingask;

/**
 * Model class holding data for an event requiring a repeating-ask interaction.
 */
public class RepeatingAskModel {
    public long id;
    public String title;
    public long startMillis;
    public long endMillis;
    public boolean allDay;
    public long calendarId;
    public String calendarAccountName;
    public String calendarAccountType;
    public String location;
    public String description;
    public String url;
    public String timezone;

    public RepeatingAskModel() {}

    /**
     * Returns the effective end time in milliseconds.
     * For all-day events, converts UTC midnight to the local midnight.
     * If endMillis is missing or zero, falls back to startMillis + 1 hour.
     */
    public long getEffectiveEndMillis() {
        if (allDay) {
            long utcEnd = endMillis > 0 ? endMillis : (startMillis > 0 ? startMillis + android.text.format.DateUtils.DAY_IN_MILLIS : 0);
            if (utcEnd > 0) {
                return com.android.calendar.Utils.convertAlldayUtcToLocal(
                        null, utcEnd, com.android.calendar.Utils.getCurrentTimezone());
            }
        }
        if (endMillis > 0) {
            return endMillis;
        }
        if (startMillis > 0) {
            return startMillis + android.text.format.DateUtils.HOUR_IN_MILLIS;
        }
        return System.currentTimeMillis();
    }
}
