package com.example.radarhumanapplication.health;

import android.graphics.Color;

/**
 * Renders the firmware's proxy-based power health into a label, emoji,
 * colour, and human-friendly cause string. Kept separate from
 * HealthCheckManager so the UI layer doesn't have to know the
 * esp_reset_reason_t enum.
 */
public final class PowerHealthFormatter {

    public static final int LEVEL_OK       = 0;
    public static final int LEVEL_WARNING  = 1;
    public static final int LEVEL_CRITICAL = 2;

    private PowerHealthFormatter() {}

    public static String levelLabel(int level) {
        switch (level) {
            case LEVEL_OK:       return "OK";
            case LEVEL_WARNING:  return "Warning";
            case LEVEL_CRITICAL: return "Critical";
            default:             return "—";
        }
    }

    public static String levelEmoji(int level) {
        switch (level) {
            case LEVEL_OK:       return "🟢";
            case LEVEL_WARNING:  return "🟡";
            case LEVEL_CRITICAL: return "🔴";
            default:             return "⚪";
        }
    }

    public static int levelColor(int level) {
        switch (level) {
            case LEVEL_OK:       return Color.parseColor("#00C853");
            case LEVEL_WARNING:  return Color.parseColor("#FFB300");
            case LEVEL_CRITICAL: return Color.parseColor("#E53935");
            default:             return Color.parseColor("#9E9E9E");
        }
    }

    /** Subset of the ESP-IDF esp_reset_reason_t enum that the firmware emits. */
    public static String resetReasonText(int code) {
        switch (code) {
            case 0:  return "unknown";
            case 1:  return "power-on";
            case 2:  return "external pin";
            case 3:  return "software";
            case 4:  return "panic";
            case 5:  return "interrupt watchdog";
            case 6:  return "task watchdog";
            case 7:  return "other watchdog";
            case 8:  return "deep sleep wake";
            case 9:  return "BROWN-OUT";
            case 10: return "SDIO";
            default: return "code=" + code;
        }
    }

    /** Free-text "why" suitable for showing under the headline status chip. */
    public static String causeLine(HealthCheckManager.HealthResult r) {
        if (r == null || !r.responded) return "No reply from device";
        StringBuilder sb = new StringBuilder();
        if (r.resetReason == 9) {
            sb.append("⚠ Last reboot was BROWN-OUT (supply dipped < 2.43 V)");
        } else if (r.brownoutCount > 0) {
            sb.append("Brown-outs in lifetime: ").append(r.brownoutCount);
        } else if (r.resetReason == 4 || r.resetReason == 5
                || r.resetReason == 6 || r.resetReason == 7) {
            sb.append("Last reboot: ").append(resetReasonText(r.resetReason));
        } else {
            sb.append("Last reboot: ").append(resetReasonText(r.resetReason));
        }
        if (!Float.isNaN(r.dieTempC)) {
            if (r.dieTempC > 80f) sb.append("  •  ⚠ Die ").append(formatTemp(r.dieTempC));
            else if (r.dieTempC > 70f) sb.append("  •  Die ").append(formatTemp(r.dieTempC));
        }
        return sb.toString();
    }

    public static String formatTemp(float t) {
        if (Float.isNaN(t)) return "—";
        return String.format(java.util.Locale.US, "%.1f °C", t);
    }
}
