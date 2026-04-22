package org.standpoint.plugin.translation;

import org.standpoint.plugin.model.Operator;
import org.standpoint.plugin.model.Sharpening;
import org.standpoint.plugin.pipeline.NormalisedAxiom;
import org.standpoint.plugin.pipeline.NormalisedKnowledgeBase;

import java.util.*;

public class PrecisificationCollector {

    private final NormalisedKnowledgeBase kb;

    public PrecisificationCollector(NormalisedKnowledgeBase kb) {
        this.kb = kb;
    }

    /**
     * Collects all standpoint names NS(K) from:
     *   - owlMap entries (normalised axioms)
     *   - sharpenings (lhs and rhs standpoint names)
     * Always includes the universal standpoint *.
     */
    public Set<String> collectStandpoints() {
        Set<String> standpoints = new LinkedHashSet<>();

        // from normalised axioms
        if (kb.owlMap != null) {
            for (NormalisedAxiom ax : kb.owlMap.values()) {
                if (ax.standpoint != null && !ax.standpoint.isEmpty()) {
                    standpoints.add(ax.standpoint);
                }
            }
        }

        // from sharpenings — lhs standpoints
        for (Sharpening s : kb.sharpenings) {
            standpoints.addAll(s.lhsStandpoints);
            // rhs standpoint — skip "0" (zero standpoint)
            if (s.rhsStandpoint != null
                    && !s.rhsStandpoint.equals("0")) {
                standpoints.add(s.rhsStandpoint);
            }
        }

        // universal standpoint always present
        standpoints.add("*");

        return standpoints;
    }

    /**
     * Collects all diamond subterms {◇_sC ∈ ST(K)} from owlMap.
     */
    public Set<DiamondSubterm> collectDiamondSubterms() {
        Set<DiamondSubterm> diamonds = new LinkedHashSet<>();
        if (kb.owlMap == null) return diamonds;

        for (Map.Entry<String, NormalisedAxiom> e : kb.owlMap.entrySet()) {
            NormalisedAxiom ax = e.getValue();

            if (ax.operator != Operator.DIAMOND || ax.owlTree == null) continue;

            // Skip duplicates — only keep canonical entries
            if (kb.canonicalKey != null) {
                String canonical = kb.canonicalKey.getOrDefault(e.getKey(), e.getKey());
                if (!canonical.equals(e.getKey())) continue;
            }

            diamonds.add(new DiamondSubterm(ax.standpoint, ax.owlTree, e.getKey()));
        }
        return diamonds;
    }
}