package org.standpoint.plugin.model;

public enum PlaceholderType {

    MODAL_PLACEHOLDER ("SP_"),   // unresolved modal sub-expression
    FRESH_CONCEPT     ("FC_"),   // fresh concept introduced by normalisation rules
    FRESH_ROLE        ("FR_"),   // fresh role introduced by normalisation rules
    FRESH_STANDPOINT  ("FS_");   // fresh standpoint introduced by Rule (1) for diamonds

    public final String prefix;

    PlaceholderType(String prefix) {
        this.prefix = prefix;
    }
}