package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.model.OWLClassExpression;

import java.util.Objects;

/**
 * Represents a diamond concept subterm ◇_sC from ST(K).
 * Used by Trans(K) to compute the precisification set Π_K.
 *
 *   - two precisifications π⁰_{s,C} and π¹_{s,C} are created per DiamondSubterm
 *   - one precisification π^a_{s,C} is created per DiamondSubterm × named individual
 */
public class DiamondSubterm {

    // s in ◇_sC — the standpoint name
    public final String standpoint;

    // C in ◇_sC — the concept expression (OWL API object)
    // May contain placeholder IRIs if SP_n references are not yet resolved
    public final OWLClassExpression concept;

    // The SP_n key this subterm corresponds to in the placeholder map
    // Used during translation to reference the correct resolved tree
    public final String placeholderKey;

    public DiamondSubterm(String standpoint,
                          OWLClassExpression concept,
                          String placeholderKey) {
        this.standpoint     = standpoint;
        this.concept        = concept;
        this.placeholderKey = placeholderKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiamondSubterm)) return false;
        DiamondSubterm that = (DiamondSubterm) o;
        return Objects.equals(standpoint, that.standpoint)
                && Objects.equals(concept, that.concept);
    }

    @Override
    public int hashCode() {
        return Objects.hash(standpoint, concept);
    }

    @Override
    public String toString() {
        return "◇_" + standpoint + "[" + concept + "]";
    }
}