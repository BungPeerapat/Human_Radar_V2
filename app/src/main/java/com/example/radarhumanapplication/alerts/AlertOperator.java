package com.example.radarhumanapplication.alerts;

/**
 * Comparison operator for distance-based alert rules. The {@code symbol} is what the user sees
 * in the rule list and the editor dropdown.
 */
public enum AlertOperator {
    LT("<"),
    LE("<="),
    EQ("="),
    GE(">="),
    GT(">");

    /** Tolerance window for {@link #EQ} matches, in millimetres. */
    public static final int EQ_TOLERANCE_MM = 200;

    public final String symbol;

    AlertOperator(String symbol) {
        this.symbol = symbol;
    }

    public boolean matches(int distanceMm, int thresholdMm) {
        switch (this) {
            case LT: return distanceMm <  thresholdMm;
            case LE: return distanceMm <= thresholdMm;
            case EQ: return Math.abs(distanceMm - thresholdMm) <= EQ_TOLERANCE_MM;
            case GE: return distanceMm >= thresholdMm;
            case GT: return distanceMm >  thresholdMm;
            default: return false;
        }
    }

    public static AlertOperator fromSymbol(String s) {
        for (AlertOperator op : values()) {
            if (op.symbol.equals(s)) return op;
        }
        return LT;
    }
}
