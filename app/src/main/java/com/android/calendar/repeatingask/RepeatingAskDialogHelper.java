package com.android.calendar.repeatingask;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarController.ViewType;
import com.android.calendar.calendarcommon2.Time;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;
import java.util.List;

import ws.xsoh.etar.R;

/**
 * Helper to display the in-app popup dialog when a repeating-ask event has ended
 * and is pending the user's input for the next occurrence.
 */
public class RepeatingAskDialogHelper {
    private static final String TAG = "RepeatingAskDialog";

    private static AlertDialog sActiveDialog = null;

    /**
     * Checks if there is a pending repeating-ask event and displays the ask popup dialog.
     */
    public static void checkAndShowPendingAsk(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        if (sActiveDialog != null && sActiveDialog.isShowing()) {
            return;
        }

        Intent intent = activity.getIntent();
        long targetEventId = -1L;
        if (intent != null && intent.hasExtra(RepeatingAskConstants.EXTRA_EVENT_ID)) {
            targetEventId = intent.getLongExtra(RepeatingAskConstants.EXTRA_EVENT_ID, -1L);
            // Clear extra so it doesn't trigger again on rotation
            intent.removeExtra(RepeatingAskConstants.EXTRA_EVENT_ID);
        }

        RepeatingAskModel targetEvent = null;
        if (targetEventId > 0) {
            targetEvent = RepeatingAskManager.getRepeatingAskEventById(activity, targetEventId);
        }

        if (targetEvent == null) {
            List<RepeatingAskModel> pendingList = RepeatingAskManager.getPendingRepeatingAskEvents(activity);
            for (RepeatingAskModel m : pendingList) {
                if (!RepeatingAskManager.isEventSnoozed(activity, m.id)) {
                    targetEvent = m;
                    break;
                }
            }
        }

        if (targetEvent == null) {
            return;
        }

        showAskDialog(activity, targetEvent);
    }

    private static void showAskDialog(Activity activity, RepeatingAskModel event) {
        String displayTitle = !TextUtils.isEmpty(event.title) ? event.title : activity.getString(R.string.no_title_label);
        String message = activity.getString(R.string.repeating_ask_dialog_message, displayTitle);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.repeating_ask_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.repeating_ask_pick_date_time, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        showDateTimePicker(activity, event);
                    }
                })
                .setNegativeButton(R.string.repeating_ask_dismiss, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        RepeatingAskManager.snoozeInAppAsk(activity, event.id);
                        RepeatingAskManager.scheduleCooldownForEvent(activity, event.id, event.title);
                    }
                })
                .setNeutralButton(R.string.repeating_ask_stop_repeating, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        RepeatingAskManager.stopRepeating(activity, event.id,
                                event.calendarAccountName, event.calendarAccountType);
                        Toast.makeText(activity, R.string.repeating_ask_stop_repeating, Toast.LENGTH_SHORT).show();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        RepeatingAskManager.snoozeInAppAsk(activity, event.id);
                        RepeatingAskManager.scheduleCooldownForEvent(activity, event.id, event.title);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        sActiveDialog = null;
                    }
                });

        sActiveDialog = builder.create();
        sActiveDialog.show();
    }

    public static void showDateTimePicker(Activity activity, RepeatingAskModel event) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || event == null) {
            return;
        }

        Calendar cal = Calendar.getInstance();
        // Default suggestion: same time tomorrow
        cal.add(Calendar.DAY_OF_YEAR, 1);

        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH);
        int day = cal.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog datePickerDialog = new DatePickerDialog(activity,
                (view, pickedYear, pickedMonth, pickedDay) -> {
                    Calendar pickedDate = Calendar.getInstance();
                    pickedDate.set(Calendar.YEAR, pickedYear);
                    pickedDate.set(Calendar.MONTH, pickedMonth);
                    pickedDate.set(Calendar.DAY_OF_MONTH, pickedDay);

                    if (event.allDay) {
                        // All-day event: normalize to midnight UTC
                        Time startTime = new Time(Time.TIMEZONE_UTC);
                        startTime.set(0, 0, 0, pickedDay, pickedMonth, pickedYear);
                        long newStart = startTime.normalize();
                        long duration = (event.endMillis > event.startMillis)
                                ? (event.endMillis - event.startMillis)
                                : DateUtils.DAY_IN_MILLIS;
                        long newEnd = newStart + duration;

                        commitNewOccurrence(activity, event, newStart, newEnd);
                    } else {
                        // Regular event: pick time next
                        showTimePicker(activity, event, pickedDate);
                    }
                }, year, month, day);

        datePickerDialog.setTitle(activity.getString(R.string.repeating_ask_pick_date_time));
        datePickerDialog.setOnCancelListener(dialog -> {
            RepeatingAskManager.snoozeInAppAsk(activity, event.id);
            RepeatingAskManager.scheduleCooldownForEvent(activity, event.id, event.title);
        });
        datePickerDialog.show();
    }

    private static void showTimePicker(Activity activity, RepeatingAskModel event, Calendar pickedDate) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || event == null) {
            return;
        }

        Calendar cal = Calendar.getInstance();
        if (event.startMillis > 0) {
            cal.setTimeInMillis(event.startMillis);
        }
        int initialHour = cal.get(Calendar.HOUR_OF_DAY);
        int initialMinute = cal.get(Calendar.MINUTE);

        boolean is24Hour = DateFormat.is24HourFormat(activity);

        TimePickerDialog timePickerDialog = new TimePickerDialog(activity,
                (view, hourOfDay, minute) -> {
                    pickedDate.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    pickedDate.set(Calendar.MINUTE, minute);
                    pickedDate.set(Calendar.SECOND, 0);
                    pickedDate.set(Calendar.MILLISECOND, 0);

                    long newStart = pickedDate.getTimeInMillis();
                    long duration = event.endMillis > event.startMillis
                            ? (event.endMillis - event.startMillis)
                            : DateUtils.HOUR_IN_MILLIS;
                    long newEnd = newStart + duration;

                    commitNewOccurrence(activity, event, newStart, newEnd);
                }, initialHour, initialMinute, is24Hour);

        timePickerDialog.setTitle(activity.getString(R.string.repeating_ask_pick_date_time));
        timePickerDialog.setOnCancelListener(dialog -> {
            RepeatingAskManager.snoozeInAppAsk(activity, event.id);
            RepeatingAskManager.scheduleCooldownForEvent(activity, event.id, event.title);
        });
        timePickerDialog.show();
    }

    private static void commitNewOccurrence(Activity activity, RepeatingAskModel event,
                                            long newStart, long newEnd) {
        RepeatingAskManager.createNextOccurrence(activity, event, newStart, newEnd);

        // Notify user
        Toast.makeText(activity, R.string.repeating_ask_occurrence_scheduled, Toast.LENGTH_SHORT).show();

        // Refresh views
        try {
            CalendarController controller = CalendarController.getInstance(activity);
            if (controller != null) {
                controller.sendEvent(activity, EventType.EVENTS_CHANGED, null, null, -1, ViewType.CURRENT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing calendar controller", e);
        }
    }
}
