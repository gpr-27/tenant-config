package com.moveinsync.config.server.state;

import com.moveinsync.config.core.change.ChangeEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// the last N committed events, so reconnecting clients catch up from memory instead of the database
@Component
public class RecentChanges {

    private final int capacity;
    private final ArrayDeque<ChangeEvent> events;

    public RecentChanges(@Value("${config.recent.capacity}") int capacity) {
        this.capacity = capacity;
        this.events = new ArrayDeque<>(capacity);
    }

    public synchronized void add(ChangeEvent event) {
        if (events.size() == capacity) {
            events.removeFirst();
        }
        events.addLast(event);
    }

    // every event after `version`, or empty when the ring no longer reaches back that far
    public synchronized Optional<List<ChangeEvent>> since(long version) {
        if (!events.isEmpty() && version < events.peekFirst().version() - 1) {
            return Optional.empty();
        }
        List<ChangeEvent> missed = new ArrayList<>();
        for (ChangeEvent event : events) {
            if (event.version() > version) {
                missed.add(event);
            }
        }
        return Optional.of(missed);
    }

    public synchronized int size() {
        return events.size();
    }

    public int capacity() {
        return capacity;
    }

    // one step, so a reader never sees the ring empty halfway through a reload and concludes it missed nothing
    public synchronized void replaceAll(List<ChangeEvent> oldestFirst) {
        events.clear();
        int skip = Math.max(0, oldestFirst.size() - capacity);
        events.addAll(oldestFirst.subList(skip, oldestFirst.size()));
    }
}
