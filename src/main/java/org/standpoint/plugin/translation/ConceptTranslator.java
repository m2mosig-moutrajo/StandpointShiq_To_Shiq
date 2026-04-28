package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.Operator;
import org.standpoint.plugin.model.Precisification;
import org.standpoint.plugin.pipeline.NormalisedAxiom;
import org.standpoint.plugin.pipeline.NormalisedKnowledgeBase;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements trans(π, C)
 *
 * trans(π, ⊤)        = ⊤
 * trans(π, ⊥)        = ⊥
 * trans(π, A)         = A^π
 * trans(π, ¬A)        = ¬A^π
 * trans(π, C⊓D)      = trans(π,C) ⊓ trans(π,D)
 * trans(π, C⊔D)      = trans(π,C) ⊔ trans(π,D)
 * trans(π, ∃R.C)     = ∃R^π.trans(π,C)
 * trans(π, ∀R.C)     = ∀R^π.trans(π,C)
 * trans(π, ≥nS.C)    = ≥nS^π.trans(π,C)
 * trans(π, ≤nS.C)    = ≤nS^π.trans(π,C)
 * trans(π, □_sC)     = ⊓_{π'∈σ(s)} AUX_D_n_{π'.id}   (SP_n with BOX)
 * trans(π, ◇_sC)     = ⊔_{π'∈σ(s)} AUX_D_n_{π'.id}   (SP_n with DIAMOND)
 *
 * SP_n placeholders are detected via AuxiliaryNames.isPlaceholder().
 * For modal cases, trans does NOT recurse into C — it produces an
 * intersection/union of auxiliary names. The link AUX_D_n_π ⊑ trans(π,C)
 * is produced separately as Type (1) axioms in TransK.
 */
public class ConceptTranslator {

    private final OWLDataFactory df;
    private final AuxiliaryNames aux;
    private final Map<String, NormalisedAxiom> owlMap;
    private final PrecisificationSet precSet;

    public ConceptTranslator(NormalisedKnowledgeBase kb,
                             AuxiliaryNames aux,
                             PrecisificationSet precSet) {
        this.df      = kb.sourceOntology
                .getOWLOntologyManager().getOWLDataFactory();
        this.aux     = aux;
        this.owlMap  = kb.owlMap;
        this.precSet = precSet;
    }

    /**
     * Main entry point — trans(π, C).
     */
    public OWLClassExpression trans(Precisification pi, OWLClassExpression concept) {
        if (concept.isOWLThing())    return df.getOWLThing();
        if (concept.isOWLNothing())  return df.getOWLNothing();

        // Named class — real concept or SP_n placeholder
        if (concept instanceof OWLClass) {
            OWLClass cls = (OWLClass) concept;
            if (AuxiliaryNames.isPlaceholder(cls)) {
                return transPlaceholder(cls);
            }
            return aux.getCopiedConcept(cls, pi);
        }

        // ¬A
        if (concept instanceof OWLObjectComplementOf) {
            OWLObjectComplementOf c = (OWLObjectComplementOf) concept;
            OWLClassExpression inner = c.getOperand();
            if (inner instanceof OWLClass) {
                OWLClass cls = (OWLClass) inner;
                if (AuxiliaryNames.isPlaceholder(cls)) {
                    return df.getOWLObjectComplementOf(
                            transPlaceholder(cls));
                }
                return df.getOWLObjectComplementOf(
                        aux.getCopiedConcept(cls, pi));
            }
            return df.getOWLObjectComplementOf(trans(pi, inner));
        }

        // C ⊓ D
        if (concept instanceof OWLObjectIntersectionOf) {
            Set<OWLClassExpression> ops = new HashSet<>();
            for (OWLClassExpression op :
                    ((OWLObjectIntersectionOf) concept).getOperands())
                ops.add(trans(pi, op));
            return df.getOWLObjectIntersectionOf(ops);
        }

        // C ⊔ D
        if (concept instanceof OWLObjectUnionOf) {
            Set<OWLClassExpression> ops = new HashSet<>();
            for (OWLClassExpression op :
                    ((OWLObjectUnionOf) concept).getOperands())
                ops.add(trans(pi, op));
            return df.getOWLObjectUnionOf(ops);
        }

        // ∃R.C
        if (concept instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom s = (OWLObjectSomeValuesFrom) concept;
            return df.getOWLObjectSomeValuesFrom(
                    transRole(pi, s.getProperty()),
                    trans(pi, s.getFiller()));
        }

        // ∀R.C
        if (concept instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom a = (OWLObjectAllValuesFrom) concept;
            return df.getOWLObjectAllValuesFrom(
                    transRole(pi, a.getProperty()),
                    trans(pi, a.getFiller()));
        }

        // ≥n S.C
        if (concept instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality m = (OWLObjectMinCardinality) concept;
            return df.getOWLObjectMinCardinality(
                    m.getCardinality(),
                    transRole(pi, m.getProperty()),
                    trans(pi, m.getFiller()));
        }

        // ≤n S.C
        if (concept instanceof OWLObjectMaxCardinality) {
            OWLObjectMaxCardinality m = (OWLObjectMaxCardinality) concept;
            return df.getOWLObjectMaxCardinality(
                    m.getCardinality(),
                    transRole(pi, m.getProperty()),
                    trans(pi, m.getFiller()));
        }

        // exact cardinality
        if (concept instanceof OWLObjectExactCardinality) {
            OWLObjectExactCardinality m = (OWLObjectExactCardinality) concept;
            return df.getOWLObjectExactCardinality(
                    m.getCardinality(),
                    transRole(pi, m.getProperty()),
                    trans(pi, m.getFiller()));
        }

        System.out.println("WARNING ConceptTranslator: unhandled type "
                + concept.getClass().getSimpleName()
                + " — " + concept);
        return concept;
    }

    /**
     * Translates a role expression at precisification π.
     * R^π for named roles, (R^π)^- for inverse roles.
     */
    public OWLObjectPropertyExpression transRole(
            Precisification pi,
            OWLObjectPropertyExpression role) {
        if (role instanceof OWLObjectProperty) {
            return aux.getCopiedRole((OWLObjectProperty) role, pi);
        }
        if (role instanceof OWLObjectInverseOf) {
            OWLObjectProperty inner = (OWLObjectProperty) ((OWLObjectInverseOf) role).getInverse();
            return df.getOWLObjectInverseOf(aux.getCopiedRole(inner, pi));
        }
        System.out.println("WARNING ConceptTranslator: unhandled role type " + role);
        return role;
    }

    /**
     * Handles trans(π, ⊙_sC) for an SP_n placeholder.
     *
     * Looks up SP_n in owlMap to get operator and standpoint s.
     * BOX     → ⊓_{π'∈σ(s)} AUX_D_n_{π'.id}
     * DIAMOND → ⊔_{π'∈σ(s)} AUX_D_n_{π'.id}
     *
     * Note: the outer π is not used here — the auxiliary names are
     * indexed by π' ranging over σ(s), not the containing π.
     */
    private OWLClassExpression transPlaceholder(OWLClass placeholder) {
        String spKey = AuxiliaryNames.getPlaceholderKey(placeholder);
        NormalisedAxiom ax = owlMap.get(spKey);

        if (ax == null) {
            System.out.println("WARNING ConceptTranslator: placeholder " + spKey + " not found in owlMap");
            return df.getOWLThing();
        }

        Set<Precisification> piSK = precSet.sigma(ax.standpoint);

        List<OWLClassExpression> auxConcepts = piSK.stream()
                .map(piPrime -> (OWLClassExpression) aux.getAuxConcept(spKey, piPrime))
                .collect(Collectors.toList());

        if (auxConcepts.isEmpty()) {
            System.out.println("WARNING ConceptTranslator: empty σ(" + ax.standpoint + ") for " + spKey);
            return df.getOWLThing();
        }

        if (auxConcepts.size() == 1) return auxConcepts.get(0);

        if (ax.operator == Operator.BOX) {
            return df.getOWLObjectIntersectionOf(new HashSet<>(auxConcepts));
        } else {
            return df.getOWLObjectUnionOf(new HashSet<>(auxConcepts));
        }
    }
}