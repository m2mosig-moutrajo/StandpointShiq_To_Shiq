package org.standpoint.plugin.model;

import java.util.List;

public class ParsedFormula {
    public final String operator;        // "box" or "diamond"
    public final String standpoint;      // standpoint name
    public final List<ParsedLiteral> literals; // expanded literals

    public ParsedFormula(String operator, String standpoint,
                         List<ParsedLiteral> literals) {
        this.operator   = operator;
        this.standpoint = standpoint;
        this.literals   = literals;
    }
}