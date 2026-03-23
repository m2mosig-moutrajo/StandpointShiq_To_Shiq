package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.loader.OntologyLoader;
import org.standpoint.plugin.normalisation.PlaceholderRestorer;
import org.standpoint.plugin.normalisation.StandpointNormaliser;
import org.standpoint.plugin.parser.PlaceholderSubstituter;
import org.standpoint.plugin.parser.PlaceholderSubstituter.Operator;
import org.standpoint.plugin.parser.PlaceholderUtil;
import org.standpoint.plugin.translation.SharpeningStatement;

import java.util.*;

public class StandpointPipeline {

    private final OWLOntology ontology;
    private final OWLDataFactory df;
    private final OWLOntologyManager manager;

    public StandpointPipeline(OWLOntology ontology) {
        this.ontology = ontology;
        this.manager  = ontology.getOWLOntologyManager();
        this.df       = manager.getOWLDataFactory();
    }
    public PipelineResult run() throws Exception {

        // Step 1 — load axioms with standpoint labels
        List<OntologyLoader.AxiomWithLabel> axiomsWithLabels =
                OntologyLoader.loadAxiomsWithLabels(ontology);
        if (axiomsWithLabels.isEmpty()) return null;

        // Step 2 — setup helper ontology for Manchester parsing
        OWLOntologyManager helperManager = OWLManager.createOWLOntologyManager();
        OWLDataFactory helperDf = helperManager.getOWLDataFactory();
        OWLOntology helperOntology = helperManager.createOntology(
                IRI.create("http://standpoint.org/helper"));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLNothing()));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLThing()));

        StandpointNormaliser standpointNormaliser =
                new StandpointNormaliser(helperDf, helperManager, helperOntology);

        // Step 3 — normalise each axiom
        Map<String, PlaceholderSubstituter.PlaceholderEntry> normalisedPlaceholderMap =
                new LinkedHashMap<>();

        for (OntologyLoader.AxiomWithLabel axiomWithLabel : axiomsWithLabels) {
            for (String standpointLabel : axiomWithLabel.standpointLabels) {

                PlaceholderSubstituter substituter = new PlaceholderSubstituter();
                String rootPlaceholderKey = substituter.substitute(standpointLabel);
                Map<String, PlaceholderSubstituter.PlaceholderEntry> placeholderMap =
                        substituter.getMap();

                registerAxiomEntitiesInHelper(placeholderMap, axiomWithLabel.axiom,
                        helperManager, helperOntology, helperDf);

                PlaceholderSubstituter.PlaceholderEntry rootEntry =
                        placeholderMap.get(rootPlaceholderKey);

                if (!rootEntry.isNegatedAxiom && !rootEntry.isAssertionAxiom) {
                    rootEntry.manchester =
                            standpointNormaliser.normaliseSubClassOf(rootEntry.manchester);
                }

                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (PlaceholderSubstituter.PlaceholderEntry entry : placeholderMap.values()) {
                        if (!entry.isRoot
                                && !entry.manchester.contains("SubClassOf")
                                && !entry.manchester.contains(" Type ")) {
                            String nnf = standpointNormaliser
                                    .applyNNFToConceptExpression(entry.manchester);
                            if (!nnf.equals(entry.manchester)) {
                                entry.manchester = nnf;
                                changed = true;
                            }
                        }
                    }
                    PlaceholderRestorer dualityRestorer = new PlaceholderRestorer(placeholderMap);
                    if (dualityRestorer.restoreModalDuality()) changed = true;
                }

                normalisedPlaceholderMap.putAll(placeholderMap);
            }
        }

        // Step 4 — Rule (1): diamond root → box + sharpening
        List<SharpeningStatement> sharpenings = new ArrayList<>();
        for (PlaceholderSubstituter.PlaceholderEntry entry : normalisedPlaceholderMap.values()) {
            if (entry.isRoot && entry.operator == Operator.DIAMOND) {
                String freshStandpoint = "FS_" + PlaceholderUtil.generateWithoutPrefix();
                sharpenings.add(new SharpeningStatement(
                        Collections.singletonList(freshStandpoint), entry.standpoint));
                entry.operator = Operator.BOX;
                entry.standpoint = freshStandpoint;
            }
        }

        // Step 5 — Rule (3): □_s[¬(C ⊑ D)] → {□_s[A ⊑ C], □_s[A ⊓ D ⊑ ⊥], □_s[⊤ ⊑ ∃R'.A]}
        List<String> negatedAxiomKeysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> snapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : snapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom || entry.isAssertionAxiom) continue;

            String inner = entry.manchester;
            int subClassOfIdx = inner.indexOf("SubClassOf");
            String C = inner.substring(0, subClassOfIdx).trim();
            String D = inner.substring(subClassOfIdx + "SubClassOf".length()).trim();

            String freshA = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshR = "FR_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(
                            IRI.create("http://standpoint.org/helper#" + freshA))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLObjectProperty(
                            IRI.create("http://standpoint.org/helper#" + freshR))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            normalisedPlaceholderMap.put(key1, new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint, freshA + " SubClassOf " + C));
            normalisedPlaceholderMap.put(key2, new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshA + " and " + D + ") SubClassOf owl:Nothing"));
            normalisedPlaceholderMap.put(key3, new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "Thing SubClassOf (" + freshR + " some " + freshA + ")"));

            negatedAxiomKeysToRemove.add(e.getKey());

            PlaceholderSubstituter.PlaceholderEntry e1 = normalisedPlaceholderMap.get(key1);
            PlaceholderSubstituter.PlaceholderEntry e2 = normalisedPlaceholderMap.get(key2);
            PlaceholderSubstituter.PlaceholderEntry e3 = normalisedPlaceholderMap.get(key3);

            e1.isRoot = true;
            e2.isRoot = true;
            e3.isRoot = true;

            e1.manchester = standpointNormaliser.normaliseSubClassOf(e1.manchester);
            e2.manchester = standpointNormaliser.normaliseSubClassOf(e2.manchester);
            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);
        }

        for (String key : negatedAxiomKeysToRemove) {
            normalisedPlaceholderMap.remove(key);
        }

        // Step 6 — Rule (10): □_s[C(a)] → □_s[NNF(C)(a)]
        //           Rule (4):  □_s[¬(C(a))] → □_s[(¬C)(a)]
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> assertionSnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : assertionSnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isAssertionAxiom) continue;

            String inner = entry.manchester;
            int typeIdx = inner.indexOf(" Type ");
            if (typeIdx == -1) continue;

            String individual = inner.substring(0, typeIdx).trim();
            String concept    = inner.substring(typeIdx + " Type ".length()).trim();

            String newConcept;
            if (entry.isNegatedAxiom) {
                // Rule (4): □_s[¬(C(a))] → □_s[(¬C)(a)]
                newConcept = "not (" + concept + ")";
            } else {
                // Rule (10): □_s[C(a)] → □_s[NNF(C)(a)]
                newConcept = standpointNormaliser.applyNNFToConceptExpression(concept);
            }

            entry.manchester       = individual + " Type " + newConcept;
            entry.isAssertionAxiom = false;
            entry.isNegatedAxiom   = false;
            entry.isRoot           = true;
        }

        return new PipelineResult(normalisedPlaceholderMap, sharpenings);
    }

    private void registerAxiomEntitiesInHelper(
            Map<String, PlaceholderSubstituter.PlaceholderEntry> placeholderMap,
            OWLAxiom axiom,
            OWLOntologyManager helperManager,
            OWLOntology helperOntology,
            OWLDataFactory helperDf) throws Exception {

        for (String placeholderKey : placeholderMap.keySet()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(
                            IRI.create("http://standpoint.org/helper#" + placeholderKey))));
        }
        for (OWLClass cls : axiom.getClassesInSignature()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(cls));
        }
        for (OWLObjectProperty prop : axiom.getObjectPropertiesInSignature()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(prop));
        }
        for (OWLNamedIndividual ind : axiom.getIndividualsInSignature()) {
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(ind));
        }
    }
}