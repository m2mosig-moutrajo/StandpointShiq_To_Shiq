package org.standpoint.plugin.pipeline.translation;

import org.semanticweb.owlapi.model.OWLClassExpression;

import java.util.Objects;

public class DiamondExpression {

    public final String standpoint;
    public OWLClassExpression concept;
    public final String placeholderKey;
    // Identifies the concept C regardless of standpoint
    // Two diamonds ◇_s2[B] and ◇_s3[B] share the same diamondId
    public String diamondId;

    public DiamondExpression(String standpoint,
                             OWLClassExpression concept,
                             String placeholderKey) {
        this.standpoint     = standpoint;
        this.concept        = concept;
        this.placeholderKey = placeholderKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiamondExpression)) return false;
        DiamondExpression that = (DiamondExpression) o;
        return Objects.equals(standpoint, that.standpoint)
                && Objects.equals(concept, that.concept);
    }

    @Override
    public int hashCode() {
        return Objects.hash(standpoint, concept);
    }

    @Override
    public String toString() {
        return "◇_" + standpoint + "["
                + (diamondId != null ? diamondId + ": " : "")
                + concept + "]";
    }
}