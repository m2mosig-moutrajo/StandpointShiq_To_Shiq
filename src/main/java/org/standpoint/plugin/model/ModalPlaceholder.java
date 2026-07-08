package org.standpoint.plugin.model;

import org.semanticweb.owlapi.model.OWLAxiom;

public class ModalPlaceholder {

    public Operator operator;
    public String standpoint;
    public String manchester;
    public boolean isRoot         = false;
    public boolean isNegatedInner = false;
    public StandpointAxiomType standpointAxiomType = StandpointAxiomType.NONE;
    public OWLAxiom originalOwlAxiom;

    public ModalPlaceholder(Operator operator, String standpoint, String manchesterExpression) {
        this.operator   = operator;
        this.standpoint = standpoint;
        this.manchester = manchesterExpression;
        this.originalOwlAxiom  = null;
    }

    /**
     * Empty-annotation constructor — no Manchester string is available.
     * Used when the standpointAxiom annotation has empty content:
     *   {@code <axiom id="F1"></axiom>}
     * The OWL axiom is used directly by the conversion step, bypassing
     * the Manchester parser entirely.
     */
    public ModalPlaceholder(Operator operator, String standpoint,
                            OWLAxiom owlAxiom) {
        this.operator          = operator;
        this.standpoint        = standpoint;
        this.manchester        = null;
        this.originalOwlAxiom  = owlAxiom;
    }

    /**
     * Returns true when Manchester content is available for parsing.
     * Returns false when the OWL axiom must be used directly.
     */
    public boolean hasManchester() {
        return manchester != null && !manchester.isEmpty();
    }

    @Override
    public String toString() {
        return "<modal op=\"" + (operator.toString().toLowerCase())
                + "\" standpoint=\"" + standpoint + "\""
                + (isNegatedInner ? " negatedInner=\"true\"" : "")
                + ">" + manchester + "</modal>"
                + (isRoot ? " [ROOT]" : "");
    }
}
