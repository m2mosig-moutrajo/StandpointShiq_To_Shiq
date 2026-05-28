package org.standpoint.plugin.translation;

import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.PlaceholderType;
import org.standpoint.plugin.model.Precisification;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;

import java.util.*;

/**
 * Manages the per-precisification vocabulary for Trans(K).
 *
 * For each π ∈ Π_K produces:
 *   A^π       = A_[π.id]          — copy of real concept name A
 *   R^π       = R_[π.id]          — copy of real role name R
 *   AUX_D_n_π = AUX_[D_n]_[π.id] — auxiliary concept for modal subterm D_n
 *
 * Resolution chain for SP_n → aux name:
 *   SP_8 → canonicalKey → SP_5 → spToDiamondId → D_2 → AUX_D_2_[π.id]
 */
public class AuxiliaryNameFactory {

    private final String ontologyBase;
    private final OWLDataFactory df;
    private final Map<String, String> canonicalKey;
    private final Map<String, String> spToDiamondId;

    private final Map<String, OWLClass> classCache = new LinkedHashMap<>();
    private final Map<String, OWLObjectProperty> propertyCache = new LinkedHashMap<>();

    public static final String AUX_PREFIX = "AUX_";

    public AuxiliaryNameFactory(StandpointKnowledgeBase kb,
                                Map<String, String> spToDiamondId,
                                OWLDataFactory df) {
        this.df             = df;
        this.canonicalKey   = kb.canonicalKey != null
                ? kb.canonicalKey : Collections.emptyMap();
        this.spToDiamondId  = spToDiamondId;

        String base = kb.sourceOntology
                .getOntologyID()
                .getOntologyIRI()
                .get()
                .toString();
        this.ontologyBase = base.endsWith("#") || base.endsWith("/")
                ? base : base + "#";
    }

    /**
     * Returns A^π — per-precisification copy of a real concept name.
     * IRI: sourceBase#[shortForm]_[π.id]
     */
    public OWLClass getCopiedConcept(OWLClass original, Precisification pi) {
        String key = original.getIRI().getShortForm() + "_" + pi.id;
        return classCache.computeIfAbsent(key,
                k -> df.getOWLClass(IRI.create(ontologyBase + k)));
    }

    /**
     * Returns R^π — per-precisification copy of a real object property.
     * IRI: sourceBase#[shortForm]_[π.id]
     */
    public OWLObjectProperty getCopiedRole(OWLObjectProperty original,
                                           Precisification pi) {
        String key = original.getIRI().getShortForm() + "_" + pi.id;
        return propertyCache.computeIfAbsent(key,
                k -> df.getOWLObjectProperty(IRI.create(ontologyBase + k)));
    }

    /**
     * Returns AUX_D_n_π — auxiliary concept name for a modal subterm.
     *
     * Resolves SP_n through canonicalKey then spToDiamondId:
     *   SP_8 → SP_5 → D_2 → AUX_D_2_[π.id]
     *
     * Used in:
     *   trans(π, □_sC) = ⊓_{π'∈σ(s)} AUX_D_n_{π'.id}
     *   trans(π, ◇_sC) = ⊔_{π'∈σ(s)} AUX_D_n_{π'.id}
     *   AUX_D_n_π ⊑ trans(π, C)   [Type (1) axioms]
     */
    public OWLClass getAuxConcept(String spKey, Precisification pi) {
        String canonical = canonicalKey.getOrDefault(spKey, spKey);
        String dn        = spToDiamondId.getOrDefault(canonical, canonical);
        String key       = AUX_PREFIX + dn + "_" + pi.id;
        return classCache.computeIfAbsent(key, k -> df.getOWLClass(IRI.create(ontologyBase + k)));
    }
}