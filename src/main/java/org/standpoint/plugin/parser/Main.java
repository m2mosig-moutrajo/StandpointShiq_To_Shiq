package org.standpoint.plugin.parser;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.loader.OntologyLoader;
import org.standpoint.plugin.normalisation.PlaceholderRestorer;
import org.standpoint.plugin.normalisation.StandpointNormaliser;
import org.standpoint.plugin.translation.SharpeningStatement;
import org.standpoint.plugin.parser.PlaceholderSubstituter.Operator;

import java.io.File;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        File owlFile = new File("C:\\Users\\Omar\\Downloads\\testSHIQH.rdf");

//        // Step 1 — validate GCI axioms and load standpoint labels (only file write happens here)
//        List<OntologyLoader.AxiomWithLabel> gciAxiomsWithLabels = OntologyLoader.loadAndValidateGCIs(owlFile);
//        if (gciAxiomsWithLabels == null) return;

        List<OntologyLoader.AxiomWithLabel> gciAxiomsWithLabels = OntologyLoader.loadGCIs(owlFile);
        if (gciAxiomsWithLabels.isEmpty()) return;

        System.out.println("\nProcessing " + gciAxiomsWithLabels.size() + " GCI axiom(s) with standpointLabel\n");

        // Step 2 — setup OWL API helper ontology for Manchester parsing
        OWLOntologyManager helperManager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = helperManager.getOWLDataFactory();
        OWLOntology helperOntology = helperManager.createOntology(
                IRI.create("http://standpoint.org/helper"));

        // Step 3 — normalise each GCI axiom
        Map<String, PlaceholderSubstituter.PlaceholderEntry> normalisedPlaceholderMap = new LinkedHashMap<>();

        for (OntologyLoader.AxiomWithLabel gciWithLabel : gciAxiomsWithLabels) {
            System.out.println("=== PROCESSING GCI AXIOM ===");
            System.out.println("Labels: " + gciWithLabel.standpointLabels.size());
            System.out.println();

            for (String standpointLabel : gciWithLabel.standpointLabels) {
                System.out.println("--- PROCESSING LABEL ---");
                System.out.println(standpointLabel);
                System.out.println();

                // Substitute modal tags with placeholders
                PlaceholderSubstituter substituter = new PlaceholderSubstituter();
                String rootPlaceholderKey = substituter.substitute(standpointLabel);
                Map<String, PlaceholderSubstituter.PlaceholderEntry> placeholderMap = substituter.getMap();

                // Register placeholders and GCI signature in helper ontology
                registerGCIEntitiesInHelper(placeholderMap, gciWithLabel.gciAxiom, helperManager, helperOntology, df);

                StandpointNormaliser standpointNormaliser = new StandpointNormaliser(df, helperManager, helperOntology);

                // Apply SubClassOf normalisation on root
                PlaceholderSubstituter.PlaceholderEntry rootEntry = placeholderMap.get(rootPlaceholderKey);
                rootEntry.manchester = standpointNormaliser.normaliseSubClassOf(rootEntry.manchester);

                // Iteratively apply NNF and restore modal duality until stable
                boolean changed = true;
                int iterations = 0;
                while (changed) {
                    changed = false;
                    iterations++;

                    for (PlaceholderSubstituter.PlaceholderEntry entry : placeholderMap.values()) {
                        if (!entry.isRoot && standpointNormaliser.findTopLevelSubClassOf(entry.manchester) == -1) {
                            String nnf = standpointNormaliser.applyNNFToConceptExpression(entry.manchester);
                            if (!nnf.equals(entry.manchester)) {
                                entry.manchester = nnf;
                                changed = true;
                            }
                        }
                    }

                    PlaceholderRestorer dualityRestorer = new PlaceholderRestorer(placeholderMap);
                    if (dualityRestorer.restoreModalDuality()) changed = true;
                }

                System.out.println("Normalised in " + iterations + " iteration(s). Root: " + rootPlaceholderKey);
                System.out.println();

                normalisedPlaceholderMap.putAll(placeholderMap);
            }
        }

        // Step 4 — Apply Rule (1): diamond root → box + sharpening
        List<SharpeningStatement> sharpenings = new ArrayList<>();

        for (PlaceholderSubstituter.PlaceholderEntry entry : normalisedPlaceholderMap.values()) {
            if (entry.isRoot && entry.operator == Operator.DIAMOND) {
                // Create fresh standpoint v
                String freshStandpoint = "v_" + entry.standpoint + "_" + PlaceholderUtil.generateWithoutPrefix();
                // Add sharpening: v ⪯ s
                sharpenings.add(new SharpeningStatement(
                        Collections.singletonList(freshStandpoint), entry.standpoint));
                // Flip root to box with fresh standpoint
                entry.operator = Operator.BOX;
                entry.standpoint = freshStandpoint;
            }
        }

        System.out.println("=== FULL NORMALISED PLACEHOLDER MAP ===\n");
        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : normalisedPlaceholderMap.entrySet()) {
            System.out.println(e.getKey() + " → " + e.getValue());
        }

        System.out.println();

        System.out.println("=== SHARPENINGS ===\n");
        for (SharpeningStatement s : sharpenings) {
            System.out.println(s);
        }
    }

    private static void registerGCIEntitiesInHelper(
            Map<String, PlaceholderSubstituter.PlaceholderEntry> placeholderMap,
            OWLSubClassOfAxiom gciAxiom,
            OWLOntologyManager helperManager,
            OWLOntology helperOntology,
            OWLDataFactory df) throws Exception {

        for (String placeholderKey : placeholderMap.keySet()) {
            OWLClass placeholderClass = df.getOWLClass(
                    IRI.create("http://standpoint.org/helper#" + placeholderKey));
            helperManager.addAxiom(helperOntology, df.getOWLDeclarationAxiom(placeholderClass));
        }

        for (OWLClass gciClass : gciAxiom.getClassesInSignature()) {
            helperManager.addAxiom(helperOntology, df.getOWLDeclarationAxiom(gciClass));
        }
        for (OWLObjectProperty gciProperty : gciAxiom.getObjectPropertiesInSignature()) {
            helperManager.addAxiom(helperOntology, df.getOWLDeclarationAxiom(gciProperty));
        }
    }
}