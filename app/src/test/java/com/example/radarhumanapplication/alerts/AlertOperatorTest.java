package com.example.radarhumanapplication.alerts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Boundary tests for {@link AlertOperator#matches(int, int)}. */
public class AlertOperatorTest {

    @Test
    public void lt_isStrict() {
        assertTrue(AlertOperator.LT.matches(999, 1000));
        assertFalse(AlertOperator.LT.matches(1000, 1000));
        assertFalse(AlertOperator.LT.matches(1001, 1000));
    }

    @Test
    public void le_includesEqual() {
        assertTrue(AlertOperator.LE.matches(999, 1000));
        assertTrue(AlertOperator.LE.matches(1000, 1000));
        assertFalse(AlertOperator.LE.matches(1001, 1000));
    }

    @Test
    public void gt_isStrict() {
        assertFalse(AlertOperator.GT.matches(999, 1000));
        assertFalse(AlertOperator.GT.matches(1000, 1000));
        assertTrue(AlertOperator.GT.matches(1001, 1000));
    }

    @Test
    public void ge_includesEqual() {
        assertFalse(AlertOperator.GE.matches(999, 1000));
        assertTrue(AlertOperator.GE.matches(1000, 1000));
        assertTrue(AlertOperator.GE.matches(1001, 1000));
    }

    @Test
    public void eq_usesTolerance() {
        int t = AlertOperator.EQ_TOLERANCE_MM;
        assertTrue(AlertOperator.EQ.matches(1000, 1000));
        assertTrue(AlertOperator.EQ.matches(1000 + t, 1000));
        assertTrue(AlertOperator.EQ.matches(1000 - t, 1000));
        assertFalse(AlertOperator.EQ.matches(1000 + t + 1, 1000));
        assertFalse(AlertOperator.EQ.matches(1000 - t - 1, 1000));
    }

    @Test
    public void fromSymbol_roundTrip() {
        for (AlertOperator op : AlertOperator.values()) {
            assertEquals(op, AlertOperator.fromSymbol(op.symbol));
        }
    }

    @Test
    public void fromSymbol_unknownDefaultsToLT() {
        assertEquals(AlertOperator.LT, AlertOperator.fromSymbol("???"));
    }
}
