package com.android.calendar.directsync;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.UUID;

/**
 * Data model for an event transferred during Direct Device-to-Device synchronization.
 */
public class DirectSyncEvent implements Serializable {
    public String uid;
    public String title;
    public String description;
    public String location;
    public long startMillis;
    public long endMillis;
    public boolean allDay;
    public String timezone;
    public String rrule;
    public long lastModified;
    public boolean isDeleted;

    public DirectSyncEvent() {
        this.uid = UUID.randomUUID().toString();
        this.lastModified = System.currentTimeMillis();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("uid", uid != null ? uid : UUID.randomUUID().toString());
        json.put("title", title != null ? title : "");
        json.put("description", description != null ? description : "");
        json.put("location", location != null ? location : "");
        json.put("startMillis", startMillis);
        json.put("endMillis", endMillis);
        json.put("allDay", allDay);
        json.put("timezone", timezone != null ? timezone : "UTC");
        json.put("rrule", rrule != null ? rrule : "");
        json.put("lastModified", lastModified);
        json.put("isDeleted", isDeleted);
        return json;
    }

    public static DirectSyncEvent fromJson(JSONObject json) {
        if (json == null) return null;
        DirectSyncEvent event = new DirectSyncEvent();
        event.uid = json.optString("uid", UUID.randomUUID().toString());
        event.title = json.optString("title", "");
        event.description = json.optString("description", "");
        event.location = json.optString("location", "");
        event.startMillis = json.optLong("startMillis", 0L);
        event.endMillis = json.optLong("endMillis", 0L);
        event.allDay = json.optBoolean("allDay", false);
        event.timezone = json.optString("timezone", "UTC");
        event.rrule = json.optString("rrule", null);
        if (event.rrule != null && event.rrule.isEmpty()) {
            event.rrule = null;
        }
        event.lastModified = json.optLong("lastModified", System.currentTimeMillis());
        event.isDeleted = json.optBoolean("isDeleted", false);
        return event;
    }
}
