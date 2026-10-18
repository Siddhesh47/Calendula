package com.android.calendar.repeatingask;

/**
 * Constants for the Repeating-Ask event type feature.
 */
public final class RepeatingAskConstants {
    private RepeatingAskConstants() {}

    /**
     * Default cooldown duration between repeated asks if the user does not respond.
     * Named constant as specified in project brief (10 hours).
     */
    public static final long REPEATING_ASK_COOLDOWN_HOURS = 10L;

    /**
     * Dedicated notification channel ID for repeating-ask events.
     */
    public static final String NOTIFICATION_CHANNEL_ID = "repeating_ask_channel";

    /**
     * Intent actions and extras.
     */
    public static final String ACTION_REPEATING_ASK_PICK = "ws.xsoh.etar.ACTION_REPEATING_ASK_PICK";
    public static final String ACTION_REPEATING_ASK_DISMISS = "ws.xsoh.etar.ACTION_REPEATING_ASK_DISMISS";
    public static final String ACTION_REPEATING_ASK_STOP = "ws.xsoh.etar.ACTION_REPEATING_ASK_STOP";
    public static final String ACTION_REPEATING_ASK_ALARM = "ws.xsoh.etar.ACTION_REPEATING_ASK_ALARM";
    public static final String ACTION_REPEATING_ASK_COOLDOWN = "ws.xsoh.etar.ACTION_REPEATING_ASK_COOLDOWN";

    public static final String EXTRA_EVENT_ID = "repeating_ask_event_id";
    public static final String EXTRA_EVENT_TITLE = "repeating_ask_event_title";
    public static final String EXTRA_EVENT_END = "repeating_ask_event_end";

    public static final String WORK_NAME_PREFIX = "repeating_ask_";
    public static final String KEY_EVENT_ID = "key_event_id";
    public static final String KEY_EVENT_TITLE = "key_event_title";
}
