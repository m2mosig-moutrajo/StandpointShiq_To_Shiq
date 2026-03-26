package org.standpoint.plugin.translation;

import java.util.ArrayList;
import java.util.List;

public class SharpeningStatement {
    public final List<String> lhsStandpoints;  // s1, s2, ..., sn
    public final String rhsStandpoint;          // s, "0",
    public final boolean isNegated;             // true for Rule (8) — ¬(s1 ∩ ... ∩ sn ⪯ u)

    public SharpeningStatement(List<String> lhsStandpoints, String rhsStandpoint) {
        this.lhsStandpoints = lhsStandpoints;
        this.rhsStandpoint  = rhsStandpoint;
        this.isNegated      = false;
    }

    public SharpeningStatement(List<String> lhsStandpoints, String rhsStandpoint,
                               boolean isNegated) {
        this.lhsStandpoints = lhsStandpoints;
        this.rhsStandpoint  = rhsStandpoint;
        this.isNegated      = isNegated;
    }

    public boolean isZero() {
        return "0".equals(rhsStandpoint);
    }

    // Parses "s1 AND s2 <= s3" or "s1 AND s2 <= 0" or "NOT(s1 AND s2 <= u)"
    public static SharpeningStatement parse(String sharpening) {
        sharpening = sharpening.trim();

        boolean isNegated = false;
        if (sharpening.startsWith("NOT(") && sharpening.endsWith(")")) {
            isNegated  = true;
            sharpening = sharpening.substring(4, sharpening.length() - 1).trim();
        }

        int leqIdx = sharpening.indexOf("<=");
        if (leqIdx == -1)
            throw new IllegalArgumentException("Invalid sharpening: " + sharpening);

        String leftPart  = sharpening.substring(0, leqIdx).trim();
        String rightPart = sharpening.substring(leqIdx + 2).trim();

        String[] parts = leftPart.split("\\s+AND\\s+");
        List<String> left = new ArrayList<>();
        for (String p : parts) left.add(p.trim());

        return new SharpeningStatement(left, rightPart, isNegated);
    }

    @Override
    public String toString() {
        String base = String.join(" AND ", lhsStandpoints) + " <= " + rhsStandpoint;
        return isNegated ? "NOT(" + base + ")" : base;
    }
}