package org.standpoint.plugin.model;

import org.semanticweb.owlapi.model.OWLClassExpression;

import java.util.Objects;

public class EntrySignature {
    final Operator operator;
    final String standpoint;
    final OWLClassExpression resolvedTree;

    public EntrySignature(Operator operator,
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