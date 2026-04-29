package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.model.OWLClassExpression;

import java.util.*;

/**
 * Assigns D_n identifiers to unique concept expressions
 * from ALL non-root modal subterms (both BOX and DIAMOND).
 *
 * Deduplication is by OWLClassExpression.equals() — order-independent.
 * Two entries with the same concept get the same D_n regardless of
 * operator or standpoint.
 *
 * Used to build spToDiamondId map for AuxiliaryNameFactory.
 * Also used to assign diamondId on DiamondExpression objects for
 * precisification naming.
 */
public class ConceptMap {

    // concept → D_n id
    private final Map<OWLClassExpression, String> conceptToId = new LinkedHashMap<>();

    // SP_n key → D_n id (for all canonical non-root entries)
    private final Map<String, String> spToId = new LinkedHashMap<>();

    private int counter = 1;

    /**
     * Adds a single entry — SP_n key + its resolved concept.
     * If the concept is already known, reuses its D_n.
     * Sets diamondId on DiamondExpression if provided.
     */
    public String addEntry(String spKey, OWLClassExpression concept) {
        String id = conceptToId.get(concept);
        if (id == null) {
            id = "D_" + counter++;
            conceptToId.put(concept, id);
        }
        spToId.put(spKey, id);
        return id;
    }

    /**
     * Builds from a set of DiamondSubterms — assigns diamondId on each.
     * Called for DIAMOND entries to set up precisification naming.
     */
    public void build(Set<DiamondExpression> diamonds) {
        for (DiamondExpression d : diamonds) {
            String id = addEntry(d.placeholderKey, d.concept);
            d.diamondId = id;
        }
    }

    /**
     * Returns the D_n id for a given SP_n key.
     * Returns null if not found.
     */
    public String getIdForSp(String spKey) {
        return spToId.get(spKey);
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