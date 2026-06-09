package org.standpoint.plugin.model;

import org.semanticweb.owlapi.model.OWLAxiom;

public class ModalPlaceholder {

    public Operator operator;
    public String standpoint;
    public String manchester;
    public boolean isRoot         = false;
    public boolean isNegatedInner = false;
    public StandpointAxiomType standpointAxiomType = StandpointAxiomType.NONE;
    public final OWLAxiom originalOwlAxiom = null;

    public ModalPlaceholder(Operator operator, String standpoint, String manchesterExpression) {
        this.operator   = operator;
        this.standpoint = standpoint;
        this.manchester = manchesterExpression;
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
