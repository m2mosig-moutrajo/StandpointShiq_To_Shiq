package org.standpoint.plugin.model;

import org.semanticweb.owlapi.model.OWLClass;

public enum PlaceholderType {

    MODAL_PLACEHOLDER ("SP_"),   // unresolved modal sub-expression
    FRESH_CONCEPT     ("FC_"),   // fresh concept introduced by normalisation rules
    FRESH_ROLE        ("FR_"),   // fresh role introduced by normalisation rules
    FRESH_STANDPOINT  ("FS_");   // fresh standpoint introduced by Rule (1) for diamonds

    public final String prefix;

    public static final String PLUGIN_NS = "http://standpoint.org/placeholder#";

    PlaceholderType(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Returns true if the given OWLClass is a modal placeholder (SP_n).
     * FC_n, FR_n, FS_n are NOT placeholders — they are real fresh entities.
     */
    public static boolean isModalPlaceholder(OWLClass cls) {
        return cls.getIRI().toString().startsWith(PLUGIN_NS)
                && cls.getIRI().getShortForm()
                .startsWith(MODAL_PLACEHOLDER.prefix);
    }
}