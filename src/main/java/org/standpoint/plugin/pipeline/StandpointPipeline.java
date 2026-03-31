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
        this.manager = ontology.getOWLOntologyManager();
        this.df = manager.getOWLDataFactory();
    }

    public PipelineResult run() throws Exception {

        // Step 1 — load axioms with standpoint labels
        List<OntologyLoader.AxiomWithLabel> axiomsWithLabels =
                OntologyLoader.loadAxiomsWithLabels(ontology);

        // Step 1.1 — load sharpenings
        List<SharpeningStatement> loadedSharpenings =
                OntologyLoader.loadSharpenings(ontology);

        if (axiomsWithLabels.isEmpty() && loadedSharpenings.isEmpty()) return null;

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

        // Step 3 — substitute and normalise each axiom
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

                // Set axiom type on root entry from loader
                PlaceholderSubstituter.PlaceholderEntry rootEntry =
                        placeholderMap.get(rootPlaceholderKey);
                rootEntry.standpointAxiomType = axiomWithLabel.axiomType;

                // Only normalise SubClassOf on non-negated CONCEPT_INCLUSION root entries
                if (!rootEntry.isNegatedAxiom
                        && rootEntry.standpointAxiomType
                        == PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION) {
                    rootEntry.manchester =
                            standpointNormaliser.normaliseSubClassOf(rootEntry.manchester);
                }

                // Per-axiom duality restoration before merging
                new PlaceholderRestorer(placeholderMap).restoreModalDuality();

                normalisedPlaceholderMap.putAll(placeholderMap);
            }
        }

        // Step 4 — Rule (1): ◇_s[µ] → {v ⪯ s, □_v[µ]}
        List<SharpeningStatement> sharpenings = new ArrayList<>();
        for (PlaceholderSubstituter.PlaceholderEntry entry : normalisedPlaceholderMap.values()) {
            if (entry.isRoot && entry.operator == Operator.DIAMOND) {
                String freshStandpoint = "FS_" + PlaceholderUtil.generateWithoutPrefix();
                sharpenings.add(new SharpeningStatement(
                        Collections.singletonList(freshStandpoint), entry.standpoint));
                entry.operator   = Operator.BOX;
                entry.standpoint = freshStandpoint;
            }
        }

        // Step 5 — Rule (3): □_s[¬(C ⊑ D)] → {□_s[A ⊑ C], □_s[A ⊓ D ⊑ ⊥], □_s[⊤ ⊑ ∃R'.A]}
        List<String> keysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> snapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : snapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION) continue;

            String inner      = entry.manchester;
            int subClassOfIdx = inner.indexOf("SubClassOf");
            String C = inner.substring(0, subClassOfIdx).trim();
            String D = inner.substring(subClassOfIdx + "SubClassOf".length()).trim();

            String freshA = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshR = "FR_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshA))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLObjectProperty(IRI.create("http://standpoint.org/helper#" + freshR))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint, freshA + " SubClassOf " + C);
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshA + " and " + D + ") SubClassOf owl:Nothing");
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "Thing SubClassOf (" + freshR + " some " + freshA + ")");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e1.manchester = standpointNormaliser.normaliseSubClassOf(e1.manchester);
            e2.manchester = standpointNormaliser.normaliseSubClassOf(e2.manchester);
            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            keysToRemove.add(e.getKey());
        }
        for (String key : keysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 6 — Rule (4): □_s[¬(C(a))] → □_s[(¬C)(a)]
        //           Rule (10): □_s[C(a)] → □_s[NNF(C)(a)]
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> assertionSnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : assertionSnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION) continue;

            String inner      = entry.manchester;
            int typeIdx       = inner.indexOf(" Type ");
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

            entry.manchester          = individual + " Type " + newConcept;
            entry.isNegatedAxiom      = false;
            entry.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.NONE;
            entry.isRoot              = true;
        }

        // Step 7 — Rule (6): □_s[¬(S ⊑ R)] → {□_s[⊤ ⊑ ∃R'.Ca], □_s[Ca ⊓ ∃R.Cb ⊑ ⊥], □_s[Ca ⊑ ∃S.Cb]}
        List<String> roleKeysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> roleSnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : roleSnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_INCLUSION) continue;

            String inner = entry.manchester;
            int idx      = inner.indexOf("SubPropertyOf");
            String S     = inner.substring(0, idx).trim();
            String R     = inner.substring(idx + "SubPropertyOf".length()).trim();

            String freshCa = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshCb = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshRp = "FR_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCa))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCb))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLObjectProperty(IRI.create("http://standpoint.org/helper#" + freshRp))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "Thing SubClassOf (" + freshRp + " some " + freshCa + ")");
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshCa + " and (" + R + " some " + freshCb + ")) SubClassOf owl:Nothing");
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    freshCa + " SubClassOf (" + S + " some " + freshCb + ")");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e1.manchester = standpointNormaliser.normaliseSubClassOf(e1.manchester);
            e2.manchester = standpointNormaliser.normaliseSubClassOf(e2.manchester);
            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            roleKeysToRemove.add(e.getKey());
        }
        for (String key : roleKeysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 8 — Rule (5): □_s[¬R(a,b)] → {□_s[Ca(a)], □_s[Cb(b)], □_s[Ca ⊓ ∃R.Cb ⊑ ⊥]}
        List<String> roleAssertionKeysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> roleAssertionSnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : roleAssertionSnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_ASSERTION) continue;

            String inner    = entry.manchester;
            String[] tokens = inner.trim().split("\\s+");
            String a        = tokens[0];
            String role     = tokens[1];
            String b        = tokens[2];

            String freshCa = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshCb = "FC_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCa))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCb))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint, a + " Type " + freshCa);
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint, b + " Type " + freshCb);
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshCa + " and (" + role + " some " + freshCb + ")) SubClassOf owl:Nothing");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_ASSERTION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            roleAssertionKeysToRemove.add(e.getKey());
        }
        for (String key : roleAssertionKeysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 9 — Rule (7): □_s[¬(Tra(R))] → {□_s[⊤ ⊑ ∃R'.Ca], □_s[Ca ⊓ ∃R.Cb ⊑ ⊥], □_s[Ca ⊑ ∃R.∃R.Cb]}
        List<String> transitivityKeysToRemove = new ArrayList<>();
        List<Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry>> transitivitySnapshot =
                new ArrayList<>(normalisedPlaceholderMap.entrySet());

        for (Map.Entry<String, PlaceholderSubstituter.PlaceholderEntry> e : transitivitySnapshot) {
            PlaceholderSubstituter.PlaceholderEntry entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType
                    != PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.ROLE_TRANSITIVITY) continue;

            String inner = entry.manchester;
            String role  = inner.replace("Transitive", "").trim();

            String freshCa = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshCb = "FC_" + PlaceholderUtil.generateWithoutPrefix();
            String freshR  = "FR_" + PlaceholderUtil.generateWithoutPrefix();

            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCa))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCb))));
            helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                    helperDf.getOWLObjectProperty(IRI.create("http://standpoint.org/helper#" + freshR))));

            String key1 = PlaceholderUtil.generate();
            String key2 = PlaceholderUtil.generate();
            String key3 = PlaceholderUtil.generate();

            PlaceholderSubstituter.PlaceholderEntry e1 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "Thing SubClassOf (" + freshR + " some " + freshCa + ")");
            PlaceholderSubstituter.PlaceholderEntry e2 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    "(" + freshCa + " and (" + role + " some " + freshCb + ")) SubClassOf owl:Nothing");
            PlaceholderSubstituter.PlaceholderEntry e3 = new PlaceholderSubstituter.PlaceholderEntry(
                    entry.operator, entry.standpoint,
                    freshCa + " SubClassOf (" + role + " some (" + role + " some " + freshCb + "))");

            e1.isRoot = true;
            e1.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e2.isRoot = true;
            e2.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            e3.isRoot = true;
            e3.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;

            normalisedPlaceholderMap.put(key1, e1);
            normalisedPlaceholderMap.put(key2, e2);
            normalisedPlaceholderMap.put(key3, e3);

            e1.manchester = standpointNormaliser.normaliseSubClassOf(e1.manchester);
            e2.manchester = standpointNormaliser.normaliseSubClassOf(e2.manchester);
            e3.manchester = standpointNormaliser.normaliseSubClassOf(e3.manchester);

            transitivityKeysToRemove.add(e.getKey());
        }
        for (String key : transitivityKeysToRemove) normalisedPlaceholderMap.remove(key);

        // Step 10 — Rule (8): ¬(s1 ∩ ... ∩ sn ⪯ u) → {v ⪯ s1, ..., v ⪯ sn, v ∩ u ⪯ 0}
        for (SharpeningStatement parsed : loadedSharpenings) {
            if (!parsed.isNegated) continue;

            String freshV = "FS_" + PlaceholderUtil.generateWithoutPrefix();

            for (String si : parsed.lhsStandpoints) {
                sharpenings.add(new SharpeningStatement(
                        Collections.singletonList(freshV), si));
            }

            List<String> vAndU = new ArrayList<>();
            vAndU.add(freshV);
            vAndU.add(parsed.rhsStandpoint);
            sharpenings.add(new SharpeningStatement(vAndU, "0"));

            System.out.println("Rule (8) applied — fresh standpoint: " + freshV);
        }

        // Step 11 — Rule (9): s1 ∩ ... ∩ sn ⪯ 0
        // → {□_s1[⊤ ⊑ A1], ..., □_sn[⊤ ⊑ An], □_*[A1 ⊓ ... ⊓ An ⊑ ⊥]}
        List<SharpeningStatement> zeroSharpenings = new ArrayList<>();
        for (SharpeningStatement s : sharpenings) {
            if (s.isZero()) zeroSharpenings.add(s);
        }
        sharpenings.removeAll(zeroSharpenings);

        for (SharpeningStatement parsed : loadedSharpenings) {
            if (!parsed.isNegated && parsed.isZero()) zeroSharpenings.add(parsed);
        }

        for (SharpeningStatement zero : zeroSharpenings) {
            List<String> freshConcepts = new ArrayList<>();

            for (String si : zero.lhsStandpoints) {
                String freshCi = "FC_" + PlaceholderUtil.generateWithoutPrefix();
                freshConcepts.add(freshCi);

                helperManager.addAxiom(helperOntology, helperDf.getOWLDeclarationAxiom(
                        helperDf.getOWLClass(IRI.create("http://standpoint.org/helper#" + freshCi))));

                String key = PlaceholderUtil.generate();
                PlaceholderSubstituter.PlaceholderEntry entry =
                        new PlaceholderSubstituter.PlaceholderEntry(
                                Operator.BOX, si, "Thing SubClassOf " + freshCi);
                entry.isRoot = true;
                entry.standpointAxiomType =
                        PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
                entry.manchester = standpointNormaliser.normaliseSubClassOf(entry.manchester);
                normalisedPlaceholderMap.put(key, entry);
            }

            String intersection = String.join(" and ", freshConcepts);
            String key = PlaceholderUtil.generate();
            PlaceholderSubstituter.PlaceholderEntry entry =
                    new PlaceholderSubstituter.PlaceholderEntry(
                            Operator.BOX, "*",
                            "(" + intersection + ") SubClassOf owl:Nothing");
            entry.isRoot = true;
            entry.standpointAxiomType =
                    PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.CONCEPT_INCLUSION;
            entry.manchester = standpointNormaliser.normaliseSubClassOf(entry.manchester);
            normalisedPlaceholderMap.put(key, entry);
        }

        // Add normal sharpenings from loaded
        for (SharpeningStatement parsed : loadedSharpenings) {
            if (!parsed.isNegated && !parsed.isZero()) {
                sharpenings.add(parsed);
            }
        }

        // Step 12 — Final NNF loop + duality restoration
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PlaceholderSubstituter.PlaceholderEntry entry : normalisedPlaceholderMap.values()) {
                if (entry.standpointAxiomType
                        == PlaceholderSubstituter.PlaceholderEntry.StandpointAxiomType.NONE) {
                    String nnf = standpointNormaliser.applyNNFToConceptExpression(entry.manchester);
                    if (!nnf.equals(entry.manchester)) {
                        entry.manchester = nnf;
                        changed = true;
                    }
                }
            }
            PlaceholderRestorer dualityRestorer = new PlaceholderRestorer(normalisedPlaceholderMap);
            if (dualityRestorer.restoreModalDuality()) changed = true;
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