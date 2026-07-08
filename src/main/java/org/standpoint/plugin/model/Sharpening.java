package org.standpoint.plugin.model;

import java.util.List;

public class Sharpening {
    public final List<String> lhsStandpoints;  // s1, s2, ..., sn
    public final String rhsStandpoint;          // s, "0",
    public final boolean isNegated;             // true for Rule (8) — ¬(s1 ∩ ... ∩ sn ⪯ u)

    public Sharpening(List<String> lhsStandpoints, String rhsStandpoint) {
        this.lhsStandpoints = lhsStandpoints;
        this.rhsStandpoint  = rhsStandpoint;
        this.isNegated      = false;
    }

    public Sharpening(List<String> lhsStandpoints, String rhsStandpoint,
                      boolean isNegated) {
        this.lhsStandpoints = lhsStandpoints;
        this.rhsStandpoint  = rhsStandpoint;
        this.isNegated      = isNegated;
    }

    public boolean isZero() {
        return "0".equals(rhsStandpoint);
    }

    @Override
    public String toString() {
        String lhs = lhsStandpoints.size() == 1
                ? lhsStandpoints.get(0)
                : String.join(" ∩ ", lhsStandpoints);
        String base = lhs + " ⪯ " + rhsStandpoint;
        return isNegated ? "¬(" + base + ")" : base;
    }
}