package org.standpoint.plugin.model;

public class ModalPlaceholder {

    public Operator operator;
    public String standpoint;
    public String manchester;
    public boolean isRoot         = false;
    public boolean isNegatedAxiom = false;
    public StandpointAxiomType standpointAxiomType = StandpointAxiomType.NONE;

    public ModalPlaceholder(Operator operator, String standpoint, String manchesterExpression) {
        this.operator   = operator;
        this.standpoint = standpoint;
        this.manchester = manchesterExpression;
    }

    @Override
    public String toString() {
        String opName = operator == Operator.BOX ? "box" : "diamond";
        return "<modal op=\"" + opName + "\" standpoint=\"" + standpoint + "\">"
                + manchester + "</modal>"
                + (isRoot ? " [ROOT]" : "");
    }
}
