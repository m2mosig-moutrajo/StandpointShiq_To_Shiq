package org.standpoint.plugin.model;

import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.standpoint.plugin.translation.DiamondSubterm;

import java.util.Objects;

/**
 * Represents one precisification (world) in Π_K.
 *
 *   Π_K contains:
 *   π_s        — one per standpoint s ∈ NS(K)
 *   π⁰_{s,C}   — first unnamed case per ◇_sC ∈ ST(K)
 *   π¹_{s,C}   — second unnamed case per ◇_sC ∈ ST(K)
 *   π^a_{s,C}  — named witness per ◇_sC ∈ ST(K) and a ∈ NI(K)
 *
 * The id field is used to generate per-precisification
 */
public class Precisification {

    // Unique identifier — used to suffix concept/role copies
    // e.g. "s1", "0_s1_SP_3", "1_s1_SP_3", "alice_s1_SP_3"
    public final String id;

    public final PrecisificationType type;

    // The standpoint t this precisification was created for.
    // Used by σ(s): this π ∈ σ(s) iff this.standpoint ∈ s^K
    public final String standpoint;

    // The diamond subterm ◇_tC this precisification witnesses.
    // Null for STANDPOINT type.
    public final DiamondSubterm diamond;

    // The named individual this precisification witnesses.
    // Null unless NAMED type.
    public final OWLNamedIndividual individual;

    public Precisification(String id,
                           PrecisificationType type,
                           String standpoint,
                           DiamondSubterm diamond,
                           OWLNamedIndividual individual) {
        this.id         = id;
        this.type       = type;
        this.standpoint = standpoint;
        this.diamond    = diamond;
        this.individual = individual;
    }

    @Override
    public String toString() {
        switch (type) {
            case STANDPOINT:  return "π_" + standpoint;
            case ANONYMOUS_0: return "π⁰_{" + standpoint + "," + diamond.placeholderKey + "}";
            case ANONYMOUS_1: return "π¹_{" + standpoint + "," + diamond.placeholderKey + "}";
            case NAMED:       return "π^" + individual.getIRI().getShortForm()
                    + "_{" + standpoint + "," + diamond.placeholderKey + "}";
            default: return id;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Precisification)) return false;
        Precisification that = (Precisification) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}