package org.standpoint.plugin.pipeline.normalisation;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.model.ModalPlaceholder;
import org.standpoint.plugin.model.StandpointAxiomType;
import org.standpoint.plugin.normaliser.ManchesterNormaliser;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

public class ManchesterToOWLConverter {

    // SP_n placeholders only — unresolved modal sub-expressions
    // FC_n, FR_n, FS_n are registered under the source ontology IRI
    // and treated as normal OWL entities by Trans(K)
    public static final String PLUGIN_NS = "http://standpoint.org/placeholder#";

    private final StandpointKnowledgeBase result;

    public ManchesterToOWLConverter(StandpointKnowledgeBase result) {
        this.result = result;
    }

    // Returns true only for SP_n — unresolved modal placeholders
    // FC_n, FR_n, FS_n are NOT placeholders — they are real fresh entities
    public static boolean isPlaceholder(OWLClass cls) {
        return cls.getIRI().toString().startsWith(PLUGIN_NS)
                && cls.getIRI().getShortForm().startsWith("SP_");
    }

    // Extracts the placeholder key from a placeholder IRI
    // e.g. "http://standpoint.org/placeholder#SP_3" → "SP_3"
    public static String getPlaceholderKey(OWLClass cls) {
        return cls.getIRI().getShortForm();
    }

    public void convert() throws Exception {

        // Step 1 — Build a fresh helper ontology
        OWLOntologyManager helperManager = OWLManager.createOWLOntologyManager();
        OWLDataFactory helperDf          = helperManager.getOWLDataFactory();
        OWLOntology helperOntology       = helperManager.createOntology();

        // Declare Thing and Nothing
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLThing()));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLNothing()));

        // Step 2 — Declare all real entities from sourceOntology
        for (OWLClass cls : result.sourceOntology.getClassesInSignature()) {
            helperManager.addAxiom(helperOntology,
                    helperDf.getOWLDeclarationAxiom(cls));
        }
        for (OWLObjectProperty prop :
                result.sourceOntology.getObjectPropertiesInSignature()) {
            helperManager.addAxiom(helperOntology,
                    helperDf.getOWLDeclarationAxiom(prop));
        }
        for (OWLNamedIndividual ind :
                result.sourceOntology.getIndividualsInSignature()) {
            helperManager.addAxiom(helperOntology,
                    helperDf.getOWLDeclarationAxiom(ind));
        }

        // Step 3 — Declare all SP_n keys as OWL classes under PLUGIN_NS
        // Short form (e.g. "SP_1") resolved automatically by Manchester parser
        Set<String> allKeys = result.manchesterMap.keySet();
        for (String key : allKeys) {
            helperManager.addAxiom(helperOntology,
                    helperDf.getOWLDeclarationAxiom(
                            helperDf.getOWLClass(
                                    IRI.create(PLUGIN_NS + key))));
        }

        // Step 4 — Declare fresh FC_n, FS_n as classes and FR_n as object
        // properties under the SOURCE ONTOLOGY IRI — they are real entities
        // introduced by normalisation rules, treated like Cat, Animal etc.
        // Trans(K) will copy them per-precisification just like real entities.
        String ontologyBase = result.sourceOntology
                .getOntologyID()
                .getOntologyIRI()
                .get()  // unwrap Optional<IRI>
                .toString();
        if (!ontologyBase.endsWith("#") && !ontologyBase.endsWith("/")) {
            ontologyBase += "#";
        }

        for (ModalPlaceholder mp : result.manchesterMap.values()) {
            for (String token : extractFreshTokens(mp.manchester)) {
                if (token.startsWith("FR_")) {
                    helperManager.addAxiom(helperOntology,
                            helperDf.getOWLDeclarationAxiom(
                                    helperDf.getOWLObjectProperty(
                                            IRI.create(ontologyBase + token))));
                } else if(token.startsWith("FC_"))  {
                    helperManager.addAxiom(helperOntology,
                            helperDf.getOWLDeclarationAxiom(
                                    helperDf.getOWLClass(
                                            IRI.create(ontologyBase + token))));
                }
            }
        }

        ManchesterNormaliser normaliser =
                new ManchesterNormaliser(helperDf, helperManager, helperOntology);

        // Step 5 — Convert each entry
        Map<String, NormalisedAxiom> resolved = new LinkedHashMap<>();

        for (Map.Entry<String, ModalPlaceholder> e :
                result.manchesterMap.entrySet()) {

            String key          = e.getKey();
            ModalPlaceholder mp = e.getValue();

            NormalisedAxiom rp = convertEntry(key, mp, normaliser);
            resolved.put(key, rp);
            PipelineLogger.log("Converted: " + key + " → " + rp);
        }

        result.owlMap = resolved;
    }

    private NormalisedAxiom convertEntry(
            String key,
            ModalPlaceholder mp,
            ManchesterNormaliser normaliser) {

        StandpointAxiomType type = mp.standpointAxiomType;

        // Check for assertion by string content FIRST —
        // type may be NONE after Rule (4)+(10) even for assertions
        if (mp.manchester.contains(" Type: ")) {
            OWLAxiom owlAxiom = tryParseAxiom(mp.manchester, normaliser, key);
            return new NormalisedAxiom(
                    mp.operator, mp.standpoint,
                    type, mp.isRoot,
                    owlAxiom, null, mp.manchester,
                    extractChildKeysFromAxiom(owlAxiom));
        }

        // NONE — nested modal node, parse as concept expression only
        if (type == StandpointAxiomType.NONE) {
            OWLClassExpression owlTree =
                    tryParseExpression(mp.manchester, normaliser, key);
            return new NormalisedAxiom(
                    mp.operator, mp.standpoint,
                    type, mp.isRoot,
                    null, owlTree, mp.manchester,
                    extractChildKeysFromExpression(owlTree));
        }

        // All other types — parse as full OWL axiom
        String axiomString = mp.manchester
                .replace("owl:Nothing", "Nothing")
                .replace("owl:Thing", "Thing");
        OWLAxiom owlAxiom = tryParseAxiom(axiomString, normaliser, key);

        return new NormalisedAxiom(
                mp.operator, mp.standpoint,
                type, mp.isRoot,
                owlAxiom, null, mp.manchester,
                extractChildKeysFromAxiom(owlAxiom));
    }

    // Parses a full Manchester axiom string into an OWLAxiom.
    // Handles Individual: frames for role assertions.
    // Returns null and logs a warning if parsing fails.
    private OWLAxiom tryParseAxiom(
            String axiomString,
            ManchesterNormaliser normaliser,
            String entryKey) {
        OWLAxiom axiom = normaliser.parseAxiom(axiomString);
        if (axiom == null) {
            PipelineLogger.log("WARNING: could not parse OWL axiom for "
                    + entryKey + " — expression: '" + axiomString + "'");
        }
        return axiom;
    }

    // Parses a concept expression string into an OWLClassExpression.
    // Returns null and logs a warning if parsing fails.
    private OWLClassExpression tryParseExpression(
            String manchesterExpr,
            ManchesterNormaliser normaliser,
            String entryKey) {
        try {
            return normaliser.parseManchesterExpression(manchesterExpr);
        } catch (Exception ex) {
            PipelineLogger.log("WARNING: could not parse OWL expression for "
                    + entryKey + ": " + ex.getMessage()
                    + " — expression: '" + manchesterExpr + "'");
            return null;
        }
    }

    // Extracts SP_n placeholder references from an OWLClassExpression.
    // FC_n, FR_n are NOT collected — they are real entities.
    private Set<String> extractChildKeysFromExpression(OWLClassExpression owlTree) {
        Set<String> children = new LinkedHashSet<>();
        if (owlTree == null) return children;
        for (OWLClass cls : owlTree.getClassesInSignature()) {
            if (isPlaceholder(cls)) {
                children.add(getPlaceholderKey(cls));
            }
        }
        return children;
    }

    // Extracts SP_n placeholder references from an OWLAxiom.
    // FC_n, FR_n are NOT collected — they are real entities.
    private Set<String> extractChildKeysFromAxiom(OWLAxiom owlAxiom) {
        Set<String> children = new LinkedHashSet<>();
        if (owlAxiom == null) return children;
        for (OWLClass cls : owlAxiom.getClassesInSignature()) {
            if (isPlaceholder(cls)) {
                children.add(getPlaceholderKey(cls));
            }
        }
        return children;
    }

    // Extracts FC_n, FR_n, FS_n tokens from a manchester string.
    private Set<String> extractFreshTokens(String manchester) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : manchester.split("[\\s():]+")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("FC_") || trimmed.startsWith("FR_")) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }
}