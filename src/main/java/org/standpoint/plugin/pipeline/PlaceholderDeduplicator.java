package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.Operator;
import org.standpoint.plugin.pipeline.ManchesterToOWLConverter;
import org.standpoint.plugin.translation.DiamondSubterm;

import java.util.*;

/**
 * Deduplicates SP_n entries in the owlMap by bottom-up structural equality.
 *
 * Pass 0 — leaf entries (childKeys = []):
 *   Compare owlTree directly using OWL API equals()
 *
 * Pass 1 — entries with 1 child:
 *   First resolve child keys to canonical, then compare
 *
 * Pass 2+ — same pattern
 *
 * Produces canonicalKey map: SP_n → canonical SP_n
 */
public class PlaceholderDeduplicator {

    private final Map<String, NormalisedAxiom> owlMap;
    private final OWLDataFactory df;
    private Map<String, String> canonicalKey;

    public PlaceholderDeduplicator(Map<String, NormalisedAxiom> owlMap,
                                   OWLDataFactory df) {
        this.owlMap = owlMap;
        this.df     = df;
    }

    public Map<String, String> deduplicate() {

        canonicalKey = new LinkedHashMap<>();
        Set<String> processed            = new LinkedHashSet<>();
        Map<EntrySignature, String> seen = new LinkedHashMap<>();

        int maxChildren = owlMap.values().stream()
                .mapToInt(ax -> ax.childKeys.size())
                .max().orElse(0);

        for (int pass = 0; pass <= maxChildren; pass++) {
            for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
                String key         = e.getKey();
                NormalisedAxiom ax = e.getValue();

                if (processed.contains(key)) continue;
                if (ax.childKeys.size() != pass) continue;
                if (!processed.containsAll(ax.childKeys)) continue;

                if (ax.isRoot) {
                    canonicalKey.put(key, key);
                    processed.add(key);
                    continue;
                }

                // Resolve child placeholders to canonical keys in owlTree
                OWLClassExpression resolvedTree = resolveTree(ax.owlTree, canonicalKey);

                EntrySignature sig = new EntrySignature(ax.operator, ax.standpoint, resolvedTree);

                if (seen.containsKey(sig)) {
                    // Duplicate — map to canonical
                    canonicalKey.put(key, seen.get(sig));
                } else {
                    // First occurrence — canonical
                    seen.put(sig, key);
                    canonicalKey.put(key, key);
                }

                processed.add(key);
            }
        }

        // Any remaining unprocessed — mark as canonical
        for (String key : owlMap.keySet()) {
            if (!processed.contains(key)) {
                canonicalKey.put(key, key);
            }
        }

        return canonicalKey;
    }

    /**
     * Walks an OWLClassExpression and replaces each placeholder IRI
     * with the IRI of its canonical representative.
     */
    private OWLClassExpression resolveTree(OWLClassExpression expr,
                                           Map<String, String> canonicalKey) {
        if (expr == null) return null;

        if (expr instanceof OWLClass) {
            OWLClass cls = (OWLClass) expr;
            if (ManchesterToOWLConverter.isPlaceholder(cls)) {
                String key   = cls.getIRI().getShortForm();
                String canon = canonicalKey.getOrDefault(key, key);
                if (canon.equals(key)) return expr;
                return df.getOWLClass(IRI.create(
                        ManchesterToOWLConverter.PLUGIN_NS + canon));
            }
            return expr;
        }

        if (expr instanceof OWLObjectComplementOf) {
            return df.getOWLObjectComplementOf(
                    resolveTree(((OWLObjectComplementOf) expr)
                            .getOperand(), canonicalKey));
        }

        if (expr instanceof OWLObjectUnionOf) {
            Set<OWLClassExpression> ops = new HashSet<>();
            for (OWLClassExpression op :
                    ((OWLObjectUnionOf) expr).getOperands())
                ops.add(resolveTree(op, canonicalKey));
            return df.getOWLObjectUnionOf(ops);
        }

        if (expr instanceof OWLObjectIntersectionOf) {
            Set<OWLClassExpression> ops = new HashSet<>();
            for (OWLClassExpression op :
                    ((OWLObjectIntersectionOf) expr).getOperands())
                ops.add(resolveTree(op, canonicalKey));
            return df.getOWLObjectIntersectionOf(ops);
        }

        if (expr instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom s = (OWLObjectSomeValuesFrom) expr;
            return df.getOWLObjectSomeValuesFrom(s.getProperty(),
                    resolveTree(s.getFiller(), canonicalKey));
        }

        if (expr instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom a = (OWLObjectAllValuesFrom) expr;
            return df.getOWLObjectAllValuesFrom(a.getProperty(),
                    resolveTree(a.getFiller(), canonicalKey));
        }

        if (expr instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality m = (OWLObjectMinCardinality) expr;
            return df.getOWLObjectMinCardinality(m.getCardinality(),
                    m.getProperty(),
                    resolveTree(m.getFiller(), canonicalKey));
        }

        if (expr instanceof OWLObjectMaxCardinality) {
            OWLObjectMaxCardinality m = (OWLObjectMaxCardinality) expr;
            return df.getOWLObjectMaxCardinality(m.getCardinality(),
                    m.getProperty(),
                    resolveTree(m.getFiller(), canonicalKey));
        }

        if (expr instanceof OWLObjectExactCardinality) {
            OWLObjectExactCardinality m = (OWLObjectExactCardinality) expr;
            return df.getOWLObjectExactCardinality(m.getCardinality(),
                    m.getProperty(),
                    resolveTree(m.getFiller(), canonicalKey));
        }

        return expr;
    }

    /**
     * Rewrites the concept field of each DiamondSubterm —
     * replaces any duplicate placeholder IRI with its canonical.
     * Must be called after deduplicate() and before ConceptMap.build().
     */
    public void resolveDiamondConcepts(Set<DiamondSubterm> diamonds) {
        for (DiamondSubterm d : diamonds) {
            if (d.concept == null) continue;
            d.concept = resolveTree(d.concept, canonicalKey);
        }
    }

    private static class EntrySignature {
        final Operator operator;
        final String standpoint;
        final OWLClassExpression resolvedTree;

        EntrySignature(Operator operator,
                       String standpoint,
                       OWLClassExpression resolvedTree) {
            this.operator    = operator;
            this.standpoint  = standpoint;
            this.resolvedTree = resolvedTree;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EntrySignature)) return false;
            EntrySignature that = (EntrySignature) o;
            return operator == that.operator
                    && Objects.equals(standpoint, that.standpoint)
                    && Objects.equals(resolvedTree, that.resolvedTree);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operator, standpoint, resolvedTree);
        }
    }
}