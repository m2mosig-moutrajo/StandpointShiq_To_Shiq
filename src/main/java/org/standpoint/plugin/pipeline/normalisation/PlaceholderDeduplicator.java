package org.standpoint.plugin.pipeline.normalisation;

import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.EntrySignature;
import org.standpoint.plugin.model.PlaceholderType;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.translation.DiamondExpression;

import java.util.*;

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

        // Group Map.Entry by childKeys size — preserves both key and ax
        Map<Integer, List<Map.Entry<String, NormalisedAxiom>>> grouped = new HashMap<>();

        for (Map.Entry<String, NormalisedAxiom> e : owlMap.entrySet()) {
            int size = e.getValue().childKeys.size();
            grouped.computeIfAbsent(size, k -> new ArrayList<>()).add(e);
        }

        // Then iterate efficiently
        for (int pass = 0; pass <= maxChildren; pass++) {
            List<Map.Entry<String, NormalisedAxiom>> list = grouped.get(pass);
            if (list == null) continue;  // no entries at this depth

            for (Map.Entry<String, NormalisedAxiom> e : list) {
                String key         = e.getKey();
                NormalisedAxiom ax = e.getValue();

                if (processed.contains(key)) continue;
                if (!processed.containsAll(ax.childKeys)) continue;

                // root entries always canonical
                if (ax.isRoot) {
                    canonicalKey.put(key, key);
                    processed.add(key);
                    continue;
                }

                OWLClassExpression resolvedTree = resolveTree(ax.owlTree, canonicalKey);
                EntrySignature sig = new EntrySignature(ax.operator, ax.standpoint, resolvedTree);

                if (seen.containsKey(sig)) {
                    canonicalKey.put(key, seen.get(sig));
                } else {
                    seen.put(sig, key);
                    canonicalKey.put(key, key);
                }

                processed.add(key);
            }
        }

        for (String key : owlMap.keySet()) {
            if (!processed.contains(key)) {
                canonicalKey.put(key, key);
            }
        }

        return canonicalKey;
    }

    /**
     * Recursively walks an OWLClassExpression and replaces every
     * placeholder IRI (SP_n) with its canonical form from canonicalKey.
     *
     * Real concepts (Animal, Dog, etc.) are never touched.
     * OWL API objects are immutable — always returns a new object when changes occur.
     */
    public OWLClassExpression resolveTree(OWLClassExpression expr,
                                          Map<String, String> canonicalKey) {
        if (expr == null) return null;

        // --- Leaf: OWLClass — either a real concept or a SP_n placeholder ---
        if (expr instanceof OWLClass) {
            return handleClass((OWLClass) expr, canonicalKey);
        }

        // --- Unary: negation — recurse into the single operand ---
        if (expr instanceof OWLObjectComplementOf) {
            OWLObjectComplementOf c = (OWLObjectComplementOf) expr;
            return df.getOWLObjectComplementOf(
                    resolveTree(c.getOperand(), canonicalKey));
        }

        // --- N-ary: union — recurse into every operand ---
        if (expr instanceof OWLObjectUnionOf) {
            return df.getOWLObjectUnionOf(
                    transformSet(((OWLObjectUnionOf) expr).getOperands(), canonicalKey));
        }

        // --- N-ary: intersection — recurse into every operand ---
        if (expr instanceof OWLObjectIntersectionOf) {
            return df.getOWLObjectIntersectionOf(
                    transformSet(((OWLObjectIntersectionOf) expr).getOperands(), canonicalKey));
        }

        // --- Restriction: ∃R.C — property untouched, recurse into filler ---
        if (expr instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom s = (OWLObjectSomeValuesFrom) expr;
            return df.getOWLObjectSomeValuesFrom(
                    s.getProperty(),
                    resolveTree(s.getFiller(), canonicalKey));
        }

        // --- Restriction: ∀R.C — property untouched, recurse into filler ---
        if (expr instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom a = (OWLObjectAllValuesFrom) expr;
            return df.getOWLObjectAllValuesFrom(
                    a.getProperty(),
                    resolveTree(a.getFiller(), canonicalKey));
        }

        // --- Cardinality restrictions: ≥n R.C / ≤n R.C / =n R.C
        //     cardinality number and property untouched, recurse into filler ---
        if (expr instanceof OWLObjectMinCardinality) {
            return rebuildCardinality((OWLObjectMinCardinality) expr, canonicalKey);
        }
        if (expr instanceof OWLObjectMaxCardinality) {
            return rebuildCardinality((OWLObjectMaxCardinality) expr, canonicalKey);
        }
        if (expr instanceof OWLObjectExactCardinality) {
            return rebuildCardinality((OWLObjectExactCardinality) expr, canonicalKey);
        }

        // --- Unknown type — return unchanged (data restrictions, nominals, etc.) ---
        return expr;
    }

    /**
     * Handles the leaf case.
     * If the class is a SP_n placeholder IRI — replace with canonical.
     * If it is a real concept — return unchanged.
     */
    private OWLClassExpression handleClass(OWLClass cls,
                                           Map<String, String> canonicalKey) {
        // Real concept — leave it alone
        if (!PlaceholderType.isModalPlaceholder(cls)) {
            return cls;
        }

        // Placeholder — resolve to canonical
        String key   = cls.getIRI().getShortForm();   // e.g. "SP_3"
        String canon = canonicalKey.getOrDefault(key, key);

        // Already canonical — return same object, no allocation needed
        if (canon.equals(key)) return cls;

        // Duplicate — build new IRI pointing to canonical
        // e.g. SP_3 → SP_1  becomes  http://standpoint.org/placeholder#SP_1
        return df.getOWLClass(IRI.create(PlaceholderType.PLUGIN_NS + canon));
    }

    /**
     * Recurses into every operand of a set (used for union and intersection).
     * Collects results into a new HashSet — OWL API will sort them internally.
     */
    private Set<OWLClassExpression> transformSet(Set<OWLClassExpression> operands,
                                                 Map<String, String> canonicalKey) {
        Set<OWLClassExpression> result = new HashSet<>(operands.size());
        for (OWLClassExpression op : operands) {
            result.add(resolveTree(op, canonicalKey));
        }
        return result;
    }

    /**
     * Rebuilds a cardinality restriction with a resolved filler.
     * Cardinality number and property are always preserved unchanged.
     * Dispatches to the correct df.getOWL* method based on subtype.
     */
    private OWLClassExpression rebuildCardinality(OWLObjectCardinalityRestriction c,
                                                  Map<String, String> canonicalKey) {
        // Only the filler can contain placeholder IRIs
        OWLClassExpression resolvedFiller = resolveTree(c.getFiller(), canonicalKey);

        if (c instanceof OWLObjectMinCardinality) {
            // ≥n R.C
            return df.getOWLObjectMinCardinality(
                    c.getCardinality(), c.getProperty(), resolvedFiller);
        }
        if (c instanceof OWLObjectMaxCardinality) {
            // ≤n R.C
            return df.getOWLObjectMaxCardinality(
                    c.getCardinality(), c.getProperty(), resolvedFiller);
        }
        if (c instanceof OWLObjectExactCardinality) {
            // =n R.C
            return df.getOWLObjectExactCardinality(
                    c.getCardinality(), c.getProperty(), resolvedFiller);
        }

        // Should never be reached — all three subtypes are handled above
        throw new IllegalArgumentException("Unsupported cardinality type: " + c.getClass().getSimpleName());
    }

    /**
     * Rewrites the concept field of each DiamondExpression —
     * replaces any duplicate placeholder IRI with its canonical.
     */
    public void resolveDiamondConcepts(Set<DiamondExpression> diamonds) {
        if (canonicalKey == null) return;
        for (DiamondExpression d : diamonds) {
            if (d.concept == null) continue;
            d.concept = resolveTree(d.concept, canonicalKey);
        }
    }

    public void setCanonicalKey(Map<String, String> canonicalKey) {
        this.canonicalKey = canonicalKey;
    }
}