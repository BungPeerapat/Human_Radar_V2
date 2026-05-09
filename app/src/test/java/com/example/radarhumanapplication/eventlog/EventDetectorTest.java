package com.example.radarhumanapplication.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class EventDetectorTest {

    @Test
    public void firstPresentFrameEmitsEnter() {
        EventDetector d = new EventDetector(0);
        List<Event> e = d.observe(1L, true, 800);
        assertEquals(1, e.size());
        assertEquals(Event.Type.ENTER, e.get(0).type);
        assertEquals(0, e.get(0).targetIndex);
        assertEquals(800, e.get(0).distanceMm);
    }

    @Test
    public void presenceFlippingOffEmitsLeave() {
        EventDetector d = new EventDetector(1);
        d.observe(1L, true, 1500);
        List<Event> e = d.observe(2L, false, 0);
        assertEquals(1, e.size());
        assertEquals(Event.Type.LEAVE, e.get(0).type);
        assertEquals(1, e.get(0).targetIndex);
    }

    @Test
    public void crossingOneMeterBoundaryEmitsMove() {
        EventDetector d = new EventDetector(0);
        // Enter inside first bucket (0..999 mm)
        List<Event> first = d.observe(1L, true, 500);
        assertEquals(Event.Type.ENTER, first.get(0).type);

        // Walking further but still inside bucket 0 — no event
        List<Event> none = d.observe(2L, true, 950);
        assertTrue(none.isEmpty());

        // Now cross 1000mm boundary -> bucket 1
        List<Event> moveOut = d.observe(3L, true, 1100);
        assertEquals(1, moveOut.size());
        assertEquals(Event.Type.MOVE, moveOut.get(0).type);
        assertEquals(1100, moveOut.get(0).distanceMm);

        // Stay in bucket 1 — no event
        List<Event> stay = d.observe(4L, true, 1900);
        assertTrue(stay.isEmpty());

        // Cross to bucket 2
        List<Event> moveOut2 = d.observe(5L, true, 2050);
        assertEquals(1, moveOut2.size());
        assertEquals(Event.Type.MOVE, moveOut2.get(0).type);

        // Cross back into bucket 1
        List<Event> back = d.observe(6L, true, 1500);
        assertEquals(1, back.size());
        assertEquals(Event.Type.MOVE, back.get(0).type);
    }

    @Test
    public void noEventsWhileTargetAbsent() {
        EventDetector d = new EventDetector(0);
        assertTrue(d.observe(1L, false, 0).isEmpty());
        assertTrue(d.observe(2L, false, 0).isEmpty());
    }

    @Test
    public void enterLeaveEnterCycle() {
        EventDetector d = new EventDetector(2);
        assertEquals(Event.Type.ENTER, d.observe(1L, true, 500).get(0).type);
        assertEquals(Event.Type.LEAVE, d.observe(2L, false, 0).get(0).type);
        assertEquals(Event.Type.ENTER, d.observe(3L, true, 1500).get(0).type);
    }
}
