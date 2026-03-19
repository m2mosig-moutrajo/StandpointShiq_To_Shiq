package org.standpoint.plugin.translation;

import java.util.List;

public class SharpeningStatement {
    public final List<String> lhsStandpoints;  // s1, s2, ..., sn
    public final String rhsStandpoint;          // s (or "0" for bottom)

    public SharpeningStatement(List<String> lhsStandpoints, String rhsStandpoint) {
        this.lhsStandpoints = lhsStandpoints;
        this.rhsStandpoint = rhsStandpoint;
    }

    @Override
    public String toString() {
        return String.join(" and ", lhsStandpoints) + " <= " + rhsStandpoint;
    }
}