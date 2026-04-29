package org.standpoint.plugin.pipeline.precisification;

import org.standpoint.plugin.model.Sharpening;

import java.util.*;

/**
 * Computes t^K for each standpoint t ∈ NS(K).
 *
 *   t^K = smallest set of standpoint names such that:
 *     (i)  contains t and * (universal standpoint)
 *     (ii) for any sharpening s1 ∩ ... ∩ sn ⪯ s in K: if {s1,...,sn} ⊆ t^K then s ∈ t^K
 *
 * Used by Trans(K) to determine which standpoints a precisification
 * π_t must satisfy axioms for.
 */
public class SharpeningClosureCalculator {

    public static final String UNIVERSAL_STANDPOINT = "*";

    // sharpenings from StandpointKnowledgeBase — normal sharpenings only
    // (zero and negated sharpenings have already been consumed by the pipeline)
    private final List<Sharpening> sharpenings;

    // all standpoint names appearing in the KB
    private final Set<String> allStandpoints;

    public SharpeningClosureCalculator(List<Sharpening> sharpenings,
                                       Set<String> allStandpoints) {
        this.sharpenings    = sharpenings;
        this.allStandpoints = allStandpoints;
    }

    /**
     * Computes t^K for every standpoint t ∈ NS(K).
     *
     * Returns Map<String, Set<String>> where:
     *   key   = standpoint name t
     *   value = t^K (the closure of t)
     *
     * Example:
     *   Sharpenings: s1 ⪯ s2, s2 ⪯ s3
     *   s1^K = {s1, s2, s3, *}
     *   s2^K = {s2, s3, *}
     *   s3^K = {s3, *}
     */
    public Map<String, Set<String>> computeAllClosures() {
        Map<String, Set<String>> closures = new LinkedHashMap<>();
        for (String standpoint : allStandpoints) {
            closures.put(standpoint, computeClosure(standpoint));
        }
        return closures;
    }

    /**
     * Computes t^K for a single standpoint t.
     *
     *   1. Start with {t, *}
     *   2. For each sharpening s1 ∩ ... ∩ sn ⪯ s:
     *      if all of s1,...,sn are already in the closure → add s
     *   3. Repeat until no changes
     */
    public Set<String> computeClosure(String standpoint) {
        Set<String> closure = new LinkedHashSet<>();
        closure.add(standpoint);
        closure.add(UNIVERSAL_STANDPOINT);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Sharpening s : sharpenings) {
                // skip zero sharpenings — rhs is "0" which is not a standpoint
                if (s.isZero()) continue;
                // skip negated — already expanded by pipeline
                if (s.isNegated) continue;

                // if all LHS standpoints are in the closure
                // and RHS is not yet in the closure → add it
                if (closure.containsAll(s.lhsStandpoints)
                        && !closure.contains(s.rhsStandpoint)) {
                    closure.add(s.rhsStandpoint);
                    changed = true;
                }
            }
        }

        return closure;
    }
}