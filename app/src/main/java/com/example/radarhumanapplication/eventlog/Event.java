package com.example.radarhumanapplication.eventlog;

/** Detection event observed by {@link EventLogger}. */
public class Event {

    public enum Type { ENTER, LEAVE, MOVE }

    public long timestamp;
    public Type type;
    public int targetIndex;
    public int distanceMm;
    public String description;

    public Event() {}

    public Event(long timestamp, Type type, int targetIndex, int distanceMm, String description) {
        this.timestamp = timestamp;
        this.type = type;
        this.targetIndex = targetIndex;
        this.distanceMm = distanceMm;
        this.description = description;
    }
}
