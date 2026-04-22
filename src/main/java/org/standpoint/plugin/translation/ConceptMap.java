package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.model.OWLClassExpression;

import java.util.*;

/**
 * Builds a concept-only deduplication map from the collected diamond subterms.
 *
 * Two diamonds ◇_s2[B] and ◇_s3[B] share the same concept B → same D_n.
 * Deduplication is by OWLClassExpression.equals() — order-independent.
 *
 * Output:
 *   conceptToId: OWLClassExpression → "D_1", "D_2", ...
 *   Each DiamondSubterm gets its diamondId field set.
 */
public class ConceptMap {

    // concept → D_n id
    private final Map<OWLClassExpression, String> conceptToId = new LinkedHashMap<>();
    private int counter = 1;

    public void build(Set<DiamondSubterm> diamonds) {
        for (DiamondSubterm d : diamonds) {
            String id = conceptToId.get(d.concept);
            if (id == null) {
                id = "D_" + counter++;
                conceptToId.put(d.concept, id);
            }
            d.diamondId = id;
        }
    }

    public int size() {
        return conceptToId.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        conceptToId.forEach((concept, id) ->
                sb.append(id).append(" → ").append(concept).append("\n"));
        return sb.toString();
    }
}