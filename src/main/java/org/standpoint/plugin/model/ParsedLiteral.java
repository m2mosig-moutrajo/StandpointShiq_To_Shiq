package org.standpoint.plugin.model;

public class ParsedLiteral {
    public final String ref;        // F1, F2, F3
    public final boolean negated;   // negated="true"

    public ParsedLiteral(String ref, boolean negated) {
        this.ref     = ref;
        this.negated = negated;
    }
}