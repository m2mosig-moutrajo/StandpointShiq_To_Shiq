package org.standpoint.plugin.pipeline.data;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.standpoint.plugin.model.Operator;
import org.standpoint.plugin.model.StandpointAxiomType;

import java.util.Set;

public class NormalisedAxiom {

    public final Operator operator;
    public final String standpoint;
    public final StandpointAxiomType axiomType;
    public final boolean isRoot;

    // Full typed OWL axiom — set for root entries.
    // e.g. OWLSubClassOfAxiom, OWLClassAssertionAxiom, etc.
    // Null for non-root entries (NONE type — nested modal nodes).
    public final OWLAxiom owlAxiom;

    // Concept expression — set for NONE entries (nested modal nodes).
    // e.g. SP_2 → ObjectAllValuesFrom(r, SP_1)
    // Null for root entries that have owlAxiom set.
    public final OWLClassExpression owlTree;

    // Original manchester string — kept for logging only.
    public final String manchester;

    // Direct SP_n placeholder references founded in owlAxiom or owlTree.
    // FC_n, FR_n are NOT included — they are real entities not placeholders.
    // Used by Trans(K) to recurse into children without re-walking the tree.
    public final Set<String> childKeys;

    public NormalisedAxiom( Operator operator,
                            String standpoint,
                            StandpointAxiomType axiomType,
                            boolean isRoot,
                            OWLAxiom owlAxiom,
                            OWLClassExpression owlTree,
                            String manchester,
                            Set<String> childKeys) {
        this.operator   = operator;
        this.standpoint = standpoint;
        this.axiomType  = axiomType;
        this.isRoot     = isRoot;
        this.owlAxiom   = owlAxiom;
        this.owlTree    = owlTree;
        this.manchester = manchester;
        this.childKeys  = childKeys;
    }

    @Override
    public String toString() {
        String opName  = operator == Operator.BOX ? "□" : "◇";
        String content = owlAxiom != null ? owlAxiom.toString()
                : owlTree  != null ? owlTree.toString()
                : manchester;
        return opName + "_" + standpoint + "[" + content + "]"
                + (isRoot ? " [ROOT]" : "")
                + (childKeys.isEmpty() ? "" : " children=" + childKeys);
    }
}