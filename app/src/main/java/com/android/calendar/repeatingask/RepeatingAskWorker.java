package com.android.calendar.repeatingask;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

/**
 * WorkManager worker that runs when a repeating-ask event ends, or on cooldown
 * (every 10 hours),
 * to trigger notifications asking for the next date & time.
 */
public class RepeatingAskWorker extends Worker {
    private static final String TAG = "RepeatingAskWorker";

    public RepeatingAskWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        long eventId = getInputData().getLong(RepeatingAskConstants.KEY_EVENT_ID, -1L);
        String eventTitle = getInputData().getString(RepeatingAskConstants.KEY_EVENT_TITLE);

        Log.d(TAG, "RepeatingAskWorker running for eventId: " + eventId);

        if (eventId > 0) {
            RepeatingAskModel event = RepeatingAskManager.getRepeatingAskEventById(context, eventId);
            if (event != null) {
                boolean isPending = RepeatingAskManager.isEventRepeatingAskAndPending(
                        context.getContentResolver(), eventId);
                long now = System.currentTimeMillis();
                long effectiveEnd = event.getEffectiveEndMillis();

                if (isPending && (effectiveEnd <= now || (effectiveEnd - now) <= 60000L)) {
                    // Show notification
                    RepeatingAskManager.showRepeatingAskNotification(
                            context, event.id, event.title, effectiveEnd);

                    // Re-schedule on 10-hour cooldown if the user doesn't respond
                    RepeatingAskManager.scheduleCooldownForEvent(context, event.id, event.title);
                } else if (isPending && effectiveEnd > now) {
                    // WorkManager ran early; re-schedule for the actual end time
                    RepeatingAskManager.scheduleAskForEvent(context, event.id, effectiveEnd, event.title);
                } else {
                    Log.d(TAG, "Event " + eventId + " is no longer pending.");
                }
            }
        } else {
            RepeatingAskManager.rescheduleAllPendingAsks(context);
        }

        return Result.success();
    }
}
