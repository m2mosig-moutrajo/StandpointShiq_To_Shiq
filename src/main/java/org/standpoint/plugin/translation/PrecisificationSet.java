package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.standpoint.plugin.model.Precisification;
import org.standpoint.plugin.model.PrecisificationType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Holds the full precisification set Π_K and provides σ(s) lookup.
 *
 * Built once from:
 *   - NS(K)   — standpoint names
 *   - ST(K)   — diamond subterms ◇_sC
 *   - NI(K)   — named individuals
 *   - t^K     — standpoint closures
 *
 * Main operations:
 *   sigma(s)  — returns all π ∈ Π_K that belong to standpoint s
 *               i.e. all π where π.standpoint ∈ s^K
 */
public class PrecisificationSet {

    private final List<Precisification> allPrecisifications;
    private final Map<String, Set<String>> closures;

    private PrecisificationSet(List<Precisification> allPrecisifications,
                               Map<String, Set<String>> closures) {
        this.allPrecisifications = allPrecisifications;
        this.closures = closures;
    }

    /**
     * Builds Π_K from the four inputs.
     */
    public static PrecisificationSet build(
            Set<String> standpoints,
            Set<DiamondSubterm> diamonds,
            Set<OWLNamedIndividual> individuals,
            Map<String, Set<String>> closures) {

        List<Precisification> all = new ArrayList<>();

        // π_s — one per standpoint
        for (String s : standpoints) {
            String id = s.equals("*") ? "star" : s;
            all.add(new Precisification(
                    id, PrecisificationType.STANDPOINT, s, null, null));
        }

        // π⁰_{s,C} and π¹_{s,C} — two per (standpoint, D_n) pair
        // π^a_{s,C} — one per (standpoint, D_n) × individual
        // Note: same D_n can appear with multiple standpoints
        for (DiamondSubterm diamond : diamonds) {
            String s  = diamond.standpoint;
            String dn = diamond.diamondId;

            // anonymous witnesses — id uses D_n not SP_n
            all.add(new Precisification(
                    "0_" + s + "_" + dn,
                    PrecisificationType.ANONYMOUS_0, s, diamond, null));
            all.add(new Precisification(
                    "1_" + s + "_" + dn,
                    PrecisificationType.ANONYMOUS_1, s, diamond, null));

            // named witnesses
            for (OWLNamedIndividual ind : individuals) {
                String indName = ind.getIRI().getShortForm();
                all.add(new Precisification(
                        indName + "_" + s + "_" + dn,
                        PrecisificationType.NAMED, s, diamond, ind));
            }
        }

        return new PrecisificationSet(all, closures);
    }

    /**
     * σ(s) — returns all precisifications belonging to standpoint s.
     * π ∈ σ(s) iff π.standpoint ∈ s^K
     */
    public Set<Precisification> sigma(String standpoint) {
        Set<String> closure = closures.getOrDefault(standpoint, Collections.singleton(standpoint));
        return allPrecisifications.stream().filter(pi -> closure.contains(pi.standpoint)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Returns all precisifications in Π_K.
     */
    public List<Precisification> getAllPrecisifications() {

        return Collections.unmodifiableList(allPrecisifications);
    }

    /**
     * Returns |Π_K| — total number of precisifications.
     */
    public int size() {
        return allPrecisifications.size();
    }
}