package org.standpoint.plugin.pipeline;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.loader.FormulaParser;
import org.standpoint.plugin.loader.OntologyLoader;
import org.standpoint.plugin.model.*;
import org.standpoint.plugin.normalisation.ModalDualityRestorer;
import org.standpoint.plugin.normalisation.ManchesterNormaliser;
import org.standpoint.plugin.normalisation.ModalExpressionDecomposer;
import org.standpoint.plugin.util.PipelineLogger;
import org.standpoint.plugin.util.PlaceholderCounter;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

public class StandpointPipeline {

    private final OWLOntology ontology;
    private final PlaceholderCounter placeholderCounter;

    // Helper ontology fields — set during run(), used across step methods
    private OWLOntologyManager helperManager;
    private OWLDataFactory helperDf;
    private OWLOntology helperOntology;
    private ManchesterNormaliser normaliser;

    public StandpointPipeline(OWLOntology ontology) {
        this.ontology           = ontology;
        this.placeholderCounter = new PlaceholderCounter();
    }

    public StandpointPipeline(OWLOntology ontology, PipelineLogger.Level logLevel) {
        this.ontology           = ontology;
        this.placeholderCounter = new PlaceholderCounter();
        PipelineLogger.setLevel(logLevel);
    }

    public PipelineResult run() throws Exception {

        // Step 1 — Load
        Map<String, OntologyLoader.AxiomWithLabel> axiomLabelMap = OntologyLoader.loadAxiomLabels(ontology);
        List<FormulaParser.ParsedFormula> formulas = OntologyLoader.loadFormulas(ontology);
        List<Sharpening> loadedSharpenings = OntologyLoader.loadSharpenings(ontology);

        logLoadedSharpenings(loadedSharpenings);
        logOriginalFormulas(formulas, axiomLabelMap);

        if (formulas.isEmpty() && loadedSharpenings.isEmpty()) return null;

        List<Sharpening> sharpenings = new ArrayList<>();

        // Step 2 — Expand formulas, apply Rule (1) for diamond operators
        List<OntologyLoader.AxiomWithLabel> expandedAxioms = expandFormulas(formulas, axiomLabelMap, sharpenings);

        logExpandedFormulas(expandedAxioms);

        // Step 3 — Build helper ontology for Manchester parsing
        buildHelperOntology();

        // Step 4 — Substitute placeholders + apply Rule (11) on GCIs
        Map<String, ModalPlaceholder> placeholderMap = substituteAndNormalise(expandedAxioms);

        // Step 5 — Apply Rule (3): negated GCIs
        applyNegatedGCI(placeholderMap);

        // Step 6 — Apply Rules (4) and (10): concept assertions
        applyConceptAssertions(placeholderMap);

        // Step 7 — Apply Rule (6): negated role inclusions
        applyNegatedRoleInclusion(placeholderMap);

        // Step 8 — Apply Rule (5): negated role assertions
        applyNegatedRoleAssertion(placeholderMap);

        // Step 9 — Apply Rule (7): negated transitivity
        applyNegatedTransitivity(placeholderMap);

        // Step 10 — Apply Rule (8): negated sharpenings
        applyNegatedSharpenings(loadedSharpenings, sharpenings);

        // Step 11 — Apply Rule (9): zero sharpenings
        applyZeroSharpenings(sharpenings, loadedSharpenings, placeholderMap);

        // Collect normal sharpenings
        collectNormalSharpenings(loadedSharpenings, sharpenings);

        // Step 12 — Final NNF loop + modal duality restoration
        applyFinalNNFLoop(placeholderMap);

        printResults(placeholderMap, sharpenings);

        return new PipelineResult(placeholderMap, sharpenings, ontology);
    }

    private List<OntologyLoader.AxiomWithLabel> expandFormulas(
            List<FormulaParser.ParsedFormula> formulas,
            Map<String, OntologyLoader.AxiomWithLabel> axiomLabelMap,
            List<Sharpening> sharpenings) {

        List<OntologyLoader.AxiomWithLabel> result = new ArrayList<>();

        for (FormulaParser.ParsedFormula formula : formulas) {
            String operator   = formula.operator;
            String standpoint = formula.standpoint;

            // Rule (1): ◇_s[φ] → fresh FS ⪯ s, □_FS[φ]
            if ("diamond".equals(operator)) {
                String freshStandpoint = "FS_" + placeholderCounter.generateWithoutPrefix();
                sharpenings.add(new Sharpening(
                        Collections.singletonList(freshStandpoint), standpoint));
                PipelineLogger.log("Rule (1) applied on formula ◇_" + standpoint
                        + " → fresh standpoint: " + freshStandpoint + " ⪯ " + standpoint);
                operator   = "box";
                standpoint = freshStandpoint;
            }

            for (FormulaParser.ParsedLiteral literal : formula.literals) {
                OntologyLoader.AxiomWithLabel axiomWithLabel =
                        axiomLabelMap.get(literal.ref);
                if (axiomWithLabel == null) {
                    PipelineLogger.result("WARNING: axiom ref '"
                            + literal.ref + "' not found!");
                    continue;
                }

                String innerContent = extractInnerContent(
                        axiomWithLabel.standpointLabels.get(0));
                String wrappedLabel = literal.negated
                        ? "<modal op=\"" + operator + "\" standpoint=\"" + standpoint
                        + "\" negatedInner=\"true\">" + innerContent + "</modal>"
                        : "<modal op=\"" + operator + "\" standpoint=\"" + standpoint
                        + "\">" + innerContent + "</modal>";

                result.add(new OntologyLoader.AxiomWithLabel(
                        axiomWithLabel.axiom,
                        Collections.singletonList(wrappedLabel),
                        axiomWithLabel.axiomType));
            }
        }
        return result;
    }

    private void buildHelperOntology() throws OWLOntologyCreationException {
        helperManager  = OWLManager.createOWLOntologyManager();
        helperDf       = helperManager.getOWLDataFactory();
        helperOntology = helperManager.createOntology(
                IRI.create("http://standpoint.org/helper"));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLNothing()));
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLThing()));
        normaliser = new ManchesterNormaliser(helperDf, helperManager, helperOntology);
    }

    private Map<String, ModalPlaceholder> substituteAndNormalise(
            List<OntologyLoader.AxiomWithLabel> expandedAxioms) throws Exception {

        Map<String, ModalPlaceholder> placeholderMap = new LinkedHashMap<>();
        PipelineLogger.log("\n=== STEP 3: SUBSTITUTION + NORMALISATION ===\n");

        for (OntologyLoader.AxiomWithLabel axiomWithLabel : expandedAxioms) {
            for (String standpointLabel : axiomWithLabel.standpointLabels) {
                PipelineLogger.log("Processing: " + standpointLabel);

                ModalExpressionDecomposer decomposer = new ModalExpressionDecomposer(placeholderCounter);
                String rootKey = decomposer.substitute(standpointLabel);
                Map<String, ModalPlaceholder> subMap = decomposer.getMap();

                registerAxiomEntitiesInHelper(subMap, axiomWithLabel.axiom);

                ModalPlaceholder rootEntry = subMap.get(rootKey);
                rootEntry.standpointAxiomType = axiomWithLabel.axiomType;

                // Rule (11): □_s[C ⊑ D] → □_s[⊤ ⊑ NNF(¬C ⊔ D)]
                if (!rootEntry.isNegatedAxiom && rootEntry.standpointAxiomType == StandpointAxiomType.CONCEPT_INCLUSION) {
                    String before = rootEntry.manchester;
                    rootEntry.manchester = normaliser.normaliseSubClassOf(rootEntry.manchester);
                    PipelineLogger.log("  Rule (11): " + before + " → " + rootEntry.manchester);
                }

                new ModalDualityRestorer(subMap, placeholderCounter).restoreModalDuality();

                PipelineLogger.log("  Root: " + rootKey + " → " + rootEntry);
                placeholderMap.putAll(subMap);
            }
        }
        return placeholderMap;
    }

    // Rule (3): □_s[¬(C ⊑ D)] → {□_s[FC ⊑ C], □_s[FC ⊓ D ⊑ ⊥], □_s[⊤ ⊑ ∃FR.FC]}
    private void applyNegatedGCI(Map<String, ModalPlaceholder> placeholderMap) {
        PipelineLogger.log("\n=== STEP 4: RULE (3) — NEGATED GCI ===\n");

        List<String> toRemove = new ArrayList<>();
        List<Map.Entry<String, ModalPlaceholder>> snapshot =
                new ArrayList<>(placeholderMap.entrySet());

        for (Map.Entry<String, ModalPlaceholder> e : snapshot) {
            ModalPlaceholder entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType != StandpointAxiomType.CONCEPT_INCLUSION)
                continue;

            int idx  = entry.manchester.indexOf("SubClassOf:");
            String C = entry.manchester.substring(0, idx).trim();
            String D = entry.manchester.substring(idx + "SubClassOf:".length()).trim();

            String freshC = "FC_" + placeholderCounter.generateWithoutPrefix();
            String freshR = "FR_" + placeholderCounter.generateWithoutPrefix();
            registerFreshClass(freshC);
            registerFreshRole(freshR);

            ModalPlaceholder e1 = rootEntry(entry,
                    freshC + " SubClassOf: " + C, StandpointAxiomType.CONCEPT_INCLUSION);
            ModalPlaceholder e2 = rootEntry(entry,
                    "(" + freshC + " and " + D + ") SubClassOf: owl:Nothing",
                    StandpointAxiomType.CONCEPT_INCLUSION);
            ModalPlaceholder e3 = rootEntry(entry,
                    "Thing SubClassOf: (" + freshR + " some " + freshC + ")",
                    StandpointAxiomType.CONCEPT_INCLUSION);

            normaliseAll(e1, e2, e3);

            String k1 = placeholderCounter.generate();
            String k2 = placeholderCounter.generate();
            String k3 = placeholderCounter.generate();
            placeholderMap.put(k1, e1);
            placeholderMap.put(k2, e2);
            placeholderMap.put(k3, e3);
            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (3) on " + e.getKey()
                    + " [" + C + " ⊑ " + D + "]:");
            PipelineLogger.log("  → " + k1 + ": " + e1);
            PipelineLogger.log("  → " + k2 + ": " + e2);
            PipelineLogger.log("  → " + k3 + ": " + e3);
        }
        toRemove.forEach(placeholderMap::remove);
    }

    // Rules (4) and (10): □_s[¬(C(a))] → □_s[(¬C)(a)]  /  □_s[C(a)] → □_s[NNF(C)(a)]
    private void applyConceptAssertions(Map<String, ModalPlaceholder> placeholderMap) {
        PipelineLogger.log("\n=== STEP 5: RULES (4)(10) — ASSERTIONS ===\n");

        for (Map.Entry<String, ModalPlaceholder> e :
                new ArrayList<>(placeholderMap.entrySet())) {
            ModalPlaceholder entry = e.getValue();
            if (entry.standpointAxiomType != StandpointAxiomType.CONCEPT_ASSERTION)
                continue;

            int typeIdx = entry.manchester.indexOf(" Type: ");
            if (typeIdx == -1) continue;

            String individual = entry.manchester.substring(0, typeIdx).trim();
            String concept = entry.manchester.substring(typeIdx + " Type: ".length()).trim();

            String newConcept;
            if (entry.isNegatedAxiom) {
                // Rule (4): wrap with not(), then Rule (10): apply NNF
                concept = "not (" + concept + ")";
            }

            // Rule (10): apply NNF to concept expression
            newConcept = normaliser.applyNNFToConceptExpression(concept);
            PipelineLogger.log("Rule (4)+(10) on " + e.getKey() + ": " + entry.manchester + " → " + individual + " Type: " + newConcept);

            entry.manchester          = individual + " Type: " + newConcept;
            entry.isNegatedAxiom      = false;
            entry.standpointAxiomType = StandpointAxiomType.CONCEPT_ASSERTION;
            entry.isRoot              = true;
        }
    }

    // Rule (6): □_s[¬(S ⊑ R)] → {□_s[⊤ ⊑ ∃FR.FCa], □_s[FCa ⊓ ∃R.FCb ⊑ ⊥], □_s[FCa ⊑ ∃S.FCb]}
    private void applyNegatedRoleInclusion(
            Map<String, ModalPlaceholder> placeholderMap) {
        PipelineLogger.log("\n=== STEP 6: RULE (6) — NEGATED ROLE INCLUSION ===\n");

        List<String> toRemove = new ArrayList<>();
        List<Map.Entry<String, ModalPlaceholder>> snapshot =
                new ArrayList<>(placeholderMap.entrySet());

        for (Map.Entry<String, ModalPlaceholder> e : snapshot) {
            ModalPlaceholder entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType != StandpointAxiomType.ROLE_INCLUSION)
                continue;

            int idx  = entry.manchester.indexOf("SubPropertyOf:");
            String S = entry.manchester.substring(0, idx).trim();
            String R = entry.manchester
                    .substring(idx + "SubPropertyOf:".length()).trim();

            String freshCa = "FC_" + placeholderCounter.generateWithoutPrefix();
            String freshCb = "FC_" + placeholderCounter.generateWithoutPrefix();
            String freshR  = "FR_" + placeholderCounter.generateWithoutPrefix();
            registerFreshClass(freshCa);
            registerFreshClass(freshCb);
            registerFreshRole(freshR);

            ModalPlaceholder e1 = rootEntry(entry,
                    "Thing SubClassOf: (" + freshR + " some " + freshCa + ")",
                    StandpointAxiomType.CONCEPT_INCLUSION);
            ModalPlaceholder e2 = rootEntry(entry,
                    "(" + freshCa + " and (" + R + " some " + freshCb
                            + ")) SubClassOf: owl:Nothing",
                    StandpointAxiomType.CONCEPT_INCLUSION);
            ModalPlaceholder e3 = rootEntry(entry,
                    freshCa + " SubClassOf: (" + S + " some " + freshCb + ")",
                    StandpointAxiomType.CONCEPT_INCLUSION);

            normaliseAll(e1, e2, e3);

            String k1 = placeholderCounter.generate();
            String k2 = placeholderCounter.generate();
            String k3 = placeholderCounter.generate();
            placeholderMap.put(k1, e1);
            placeholderMap.put(k2, e2);
            placeholderMap.put(k3, e3);
            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (6) on " + e.getKey()
                    + " [" + S + " ⊑ " + R + "]:");
            PipelineLogger.log("  → " + k1 + ": " + e1);
            PipelineLogger.log("  → " + k2 + ": " + e2);
            PipelineLogger.log("  → " + k3 + ": " + e3);
        }
        toRemove.forEach(placeholderMap::remove);
    }

    // Rule (5): □_s[¬R(a,b)] → {□_s[FCa(a)], □_s[FCb(b)], □_s[FCa ⊓ ∃R.FCb ⊑ ⊥]}
    private void applyNegatedRoleAssertion(
            Map<String, ModalPlaceholder> placeholderMap) {
        PipelineLogger.log("\n=== STEP 7: RULE (5) — NEGATED ROLE ASSERTION ===\n");

        List<String> toRemove = new ArrayList<>();
        List<Map.Entry<String, ModalPlaceholder>> snapshot =
                new ArrayList<>(placeholderMap.entrySet());

        for (Map.Entry<String, ModalPlaceholder> e : snapshot) {
            ModalPlaceholder entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType != StandpointAxiomType.ROLE_ASSERTION)
                continue;

            String stripped = entry.manchester.replace("Individual:", "").replace("Facts:", "").trim();
            String[] tokens = stripped.trim().split("\\s+");
            String a    = tokens[0];  // Alice
            String role = tokens[1];  // knows
            String b    = tokens[2];  // Bob

            String freshCa = "FC_" + placeholderCounter.generateWithoutPrefix();
            String freshCb = "FC_" + placeholderCounter.generateWithoutPrefix();
            registerFreshClass(freshCa);
            registerFreshClass(freshCb);

            ModalPlaceholder e1 = rootEntry(entry,
                    a + " Type: " + freshCa, StandpointAxiomType.CONCEPT_ASSERTION);
            ModalPlaceholder e2 = rootEntry(entry,
                    b + " Type: " + freshCb, StandpointAxiomType.CONCEPT_ASSERTION);
            ModalPlaceholder e3 = rootEntry(entry,
                    "(" + freshCa + " and (" + role + " some " + freshCb
                            + ")) SubClassOf: owl:Nothing",
                    StandpointAxiomType.CONCEPT_INCLUSION);

            e3.manchester = normaliser.normaliseSubClassOf(e3.manchester);

            String k1 = placeholderCounter.generate();
            String k2 = placeholderCounter.generate();
            String k3 = placeholderCounter.generate();
            placeholderMap.put(k1, e1);
            placeholderMap.put(k2, e2);
            placeholderMap.put(k3, e3);
            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (5) on " + e.getKey()
                    + " [" + a + " " + role + " " + b + "]:");
            PipelineLogger.log("  → " + k1 + ": " + e1);
            PipelineLogger.log("  → " + k2 + ": " + e2);
            PipelineLogger.log("  → " + k3 + ": " + e3);
        }
        toRemove.forEach(placeholderMap::remove);
    }

    // Rule (7): □_s[¬Trans(R)] → {□_s[⊤ ⊑ ∃FR.FCa], □_s[FCa ⊓ ∃R.FCb ⊑ ⊥],
    //                              □_s[FCa ⊑ ∃R.∃R.FCb]}
    private void applyNegatedTransitivity(
            Map<String, ModalPlaceholder> placeholderMap) {
        PipelineLogger.log("\n=== STEP 8: RULE (7) — NEGATED TRANSITIVITY ===\n");

        List<String> toRemove = new ArrayList<>();
        List<Map.Entry<String, ModalPlaceholder>> snapshot =
                new ArrayList<>(placeholderMap.entrySet());

        for (Map.Entry<String, ModalPlaceholder> e : snapshot) {
            ModalPlaceholder entry = e.getValue();
            if (!entry.isNegatedAxiom
                    || entry.standpointAxiomType != StandpointAxiomType.ROLE_TRANSITIVITY)
                continue;

            String role = entry.manchester.replace("Transitive", "").trim();

            String freshCa = "FC_" + placeholderCounter.generateWithoutPrefix();
            String freshCb = "FC_" + placeholderCounter.generateWithoutPrefix();
            String freshR  = "FR_" + placeholderCounter.generateWithoutPrefix();
            registerFreshClass(freshCa);
            registerFreshClass(freshCb);
            registerFreshRole(freshR);

            ModalPlaceholder e1 = rootEntry(entry,
                    "Thing SubClassOf: (" + freshR + " some " + freshCa + ")",
                    StandpointAxiomType.CONCEPT_INCLUSION);
            ModalPlaceholder e2 = rootEntry(entry,
                    "(" + freshCa + " and (" + role + " some " + freshCb
                            + ")) SubClassOf: owl:Nothing",
                    StandpointAxiomType.CONCEPT_INCLUSION);
            ModalPlaceholder e3 = rootEntry(entry,
                    freshCa + " SubClassOf: (" + role + " some ("
                            + role + " some " + freshCb + "))",
                    StandpointAxiomType.CONCEPT_INCLUSION);

            normaliseAll(e1, e2, e3);

            String k1 = placeholderCounter.generate();
            String k2 = placeholderCounter.generate();
            String k3 = placeholderCounter.generate();
            placeholderMap.put(k1, e1);
            placeholderMap.put(k2, e2);
            placeholderMap.put(k3, e3);
            toRemove.add(e.getKey());

            PipelineLogger.log("Rule (7) on " + e.getKey()
                    + " [Tra(" + role + ")]:");
            PipelineLogger.log("  → " + k1 + ": " + e1);
            PipelineLogger.log("  → " + k2 + ": " + e2);
            PipelineLogger.log("  → " + k3 + ": " + e3);
        }
        toRemove.forEach(placeholderMap::remove);
    }

    // Rule (8): ¬(s1 ∩ ... ∩ sn ⪯ u) → {FS ⪯ s1, ..., FS ⪯ sn, FS ∩ u ⪯ 0}
    private void applyNegatedSharpenings(List<Sharpening> loadedSharpenings,
                                         List<Sharpening> sharpenings) {
        PipelineLogger.log("\n=== STEP 9: RULE (8) — NEGATED SHARPENING ===\n");

        for (Sharpening s : loadedSharpenings) {
            if (!s.isNegated) continue;

            String freshV = "FS_" + placeholderCounter.generateWithoutPrefix();
            for (String si : s.lhsStandpoints) {
                sharpenings.add(new Sharpening(
                        Collections.singletonList(freshV), si));
                PipelineLogger.log("  → " + freshV + " ⪯ " + si);
            }
            List<String> vAndU = new ArrayList<>();
            vAndU.add(freshV);
            vAndU.add(s.rhsStandpoint);
            sharpenings.add(new Sharpening(vAndU, "0"));

            PipelineLogger.log("Rule (8) applied — fresh standpoint: " + freshV);
            PipelineLogger.log("  → " + freshV + " ∩ " + s.rhsStandpoint + " ⪯ 0");
        }
    }

    // Rule (9): s1 ∩ ... ∩ sn ⪯ 0 → {□_s1[⊤ ⊑ FCi], ..., □_*[FC1 ⊓ ... ⊓ FCn ⊑ ⊥]}
    private void applyZeroSharpenings(List<Sharpening> sharpenings,
                                      List<Sharpening> loadedSharpenings,
                                      Map<String, ModalPlaceholder> placeholderMap) throws Exception {
        PipelineLogger.log("\n=== STEP 10: RULE (9) — ZERO SHARPENING ===\n");

        List<Sharpening> zeroSharpenings = new ArrayList<>();
        for (Sharpening s : sharpenings) {
            if (s.isZero()) zeroSharpenings.add(s);
        }
        sharpenings.removeAll(zeroSharpenings);
        for (Sharpening s : loadedSharpenings) {
            if (!s.isNegated && s.isZero()) zeroSharpenings.add(s);
        }

        for (Sharpening zero : zeroSharpenings) {

            if (zero.lhsStandpoints.contains("0")) {
                PipelineLogger.log("Rule (9) skipped (trivial 0 ⪯ 0): " + zero);
                continue;
            }

            PipelineLogger.log("Rule (9) on: " + zero);
            List<String> freshConcepts = new ArrayList<>();

            for (String si : zero.lhsStandpoints) {
                String freshCi = "FC_" + placeholderCounter.generateWithoutPrefix();
                freshConcepts.add(freshCi);
                registerFreshClass(freshCi);

                String key = placeholderCounter.generate();
                ModalPlaceholder entry = rootEntry(
                        Operator.BOX, si,
                        "Thing SubClassOf: " + freshCi,
                        StandpointAxiomType.CONCEPT_INCLUSION);
                entry.manchester = normaliser.normaliseSubClassOf(entry.manchester);
                placeholderMap.put(key, entry);
                PipelineLogger.log("  → " + key + ": □_" + si + "[⊤ ⊑ " + freshCi + "]");
            }

            String intersection = String.join(" and ", freshConcepts);
            String key = placeholderCounter.generate();
            ModalPlaceholder global = rootEntry(
                    Operator.BOX, "*",
                    "(" + intersection + ") SubClassOf: owl:Nothing",
                    StandpointAxiomType.CONCEPT_INCLUSION);
            global.manchester = normaliser.normaliseSubClassOf(global.manchester);
            placeholderMap.put(key, global);
            PipelineLogger.log("  → " + key + ": □_*[" + intersection + " ⊑ ⊥]");
        }
    }

    private void collectNormalSharpenings(List<Sharpening> loadedSharpenings,
                                          List<Sharpening> sharpenings) {
        for (Sharpening s : loadedSharpenings) {
            if (!s.isNegated && !s.isZero()) {
                sharpenings.add(s);
                PipelineLogger.log("Normal sharpening added: " + s);
            }
        }
    }

    private void applyFinalNNFLoop(Map<String, ModalPlaceholder> placeholderMap) {
        PipelineLogger.log("\n=== STEP 11: FINAL NNF LOOP + DUALITY RESTORATION ===\n");

        boolean changed = true;
        int iteration   = 0;
        while (changed) {
            changed = false;
            iteration++;
            for (ModalPlaceholder entry : placeholderMap.values()) {
                if (entry.standpointAxiomType == StandpointAxiomType.NONE) {
                    String before = entry.manchester;
                    String nnf;

                    int typeIdx = entry.manchester.indexOf(" Type: ");
                    if (typeIdx != -1) {
                        // Assertion — extract concept part, apply NNF, reassemble
                        String individual = entry.manchester.substring(0, typeIdx).trim();
                        String concept    = entry.manchester
                                .substring(typeIdx + " Type: ".length()).trim();
                        String nnfConcept = normaliser.applyNNFToConceptExpression(concept);
                        nnf = individual + " Type: " + nnfConcept;
                    } else {
                        // Pure concept expression
                        nnf = normaliser.applyNNFToConceptExpression(entry.manchester);
                    }

                    if (!nnf.equals(entry.manchester)) {
                        entry.manchester = nnf;
                        changed = true;
                        PipelineLogger.log("  NNF iteration " + iteration + ": " + before + " → " + nnf);
                    }
                }
            }
            ModalDualityRestorer restorer = new ModalDualityRestorer(placeholderMap, placeholderCounter);
            if (restorer.restoreModalDuality()) {
                changed = true;
                PipelineLogger.log("  Duality restored in iteration " + iteration);
            }
        }
        PipelineLogger.log("NNF loop completed in " + iteration + " iteration(s)");
    }

    // Creates a root ModalPlaceholder inheriting operator and standpoint from parent
    private ModalPlaceholder rootEntry(ModalPlaceholder parent,
                                       String manchester,
                                       StandpointAxiomType type) {
        ModalPlaceholder e = new ModalPlaceholder(
                parent.operator, parent.standpoint, manchester);
        e.isRoot              = true;
        e.standpointAxiomType = type;
        return e;
    }

    // Creates a root ModalPlaceholder with explicit operator and standpoint
    private ModalPlaceholder rootEntry(Operator operator, String standpoint,
                                       String manchester, StandpointAxiomType type) {
        ModalPlaceholder e = new ModalPlaceholder(operator, standpoint, manchester);
        e.isRoot              = true;
        e.standpointAxiomType = type;
        return e;
    }

    // Applies normaliseSubClassOf to all three entries in one call
    private void normaliseAll(ModalPlaceholder e1,
                              ModalPlaceholder e2,
                              ModalPlaceholder e3) {
        e1.manchester = normaliser.normaliseSubClassOf(e1.manchester);
        e2.manchester = normaliser.normaliseSubClassOf(e2.manchester);
        e3.manchester = normaliser.normaliseSubClassOf(e3.manchester);
    }

    private void registerFreshClass(String name) {
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLClass(
                        IRI.create("http://standpoint.org/helper#" + name))));
    }

    private void registerFreshRole(String name) {
        helperManager.addAxiom(helperOntology,
                helperDf.getOWLDeclarationAxiom(helperDf.getOWLObjectProperty(
                        IRI.create("http://standpoint.org/helper#" + name))));
    }

    private void registerAxiomEntitiesInHelper(
            Map<String, ModalPlaceholder> placeholderMap,
            OWLAxiom axiom) {
        for (String key : placeholderMap.keySet()) {
            helperManager.addAxiom(helperOntology,
                    helperDf.getOWLDeclarationAxiom(helperDf.getOWLClass(
                            IRI.create("http://standpoint.org/helper#" + key))));
        }
        axiom.getClassesInSignature().forEach(c ->
                helperManager.addAxiom(helperOntology,
                        helperDf.getOWLDeclarationAxiom(c)));
        axiom.getObjectPropertiesInSignature().forEach(p ->
                helperManager.addAxiom(helperOntology,
                        helperDf.getOWLDeclarationAxiom(p)));
        axiom.getIndividualsInSignature().forEach(i ->
                helperManager.addAxiom(helperOntology,
                        helperDf.getOWLDeclarationAxiom(i)));
    }

    private void logLoadedSharpenings(List<Sharpening> sharpenings) {
        PipelineLogger.log("\n=== LOADED SHARPENINGS ===\n");
        if (sharpenings.isEmpty()) {
            PipelineLogger.log("(none)");
        } else {
            sharpenings.forEach(s -> PipelineLogger.log(s.toString()));
        }
        PipelineLogger.log("");
    }

    private void logOriginalFormulas(
            List<FormulaParser.ParsedFormula> formulas,
            Map<String, OntologyLoader.AxiomWithLabel> axiomLabelMap) {

        PipelineLogger.log("\n=== ORIGINAL FORMULAS ===\n");
        for (FormulaParser.ParsedFormula formula : formulas) {
            StringBuilder sb = new StringBuilder();
            String symbol = "box".equals(formula.operator) ? "□" : "◇";
            sb.append(symbol).append("_").append(formula.standpoint).append("[");
            for (int i = 0; i < formula.literals.size(); i++) {
                FormulaParser.ParsedLiteral lit = formula.literals.get(i);
                if (lit.negated) sb.append("¬");
                sb.append(lit.ref);
                if (i < formula.literals.size() - 1) sb.append(" ∧ ");
            }
            sb.append("]");
            PipelineLogger.log(sb.toString());
        }
        PipelineLogger.log("");

        PipelineLogger.log("\n=== AXIOM LABELS (XML) ===\n");
        axiomLabelMap.forEach((k, v) ->
                PipelineLogger.log(k + " → " + v.standpointLabels.get(0)));
        PipelineLogger.log("");

        PipelineLogger.log("\n=== AXIOM LABELS (READABLE) ===\n");
        axiomLabelMap.forEach((k, v) ->
                PipelineLogger.log(k + " → " + formatAxiomLabel(v.standpointLabels.get(0))));
        PipelineLogger.log("");
    }

    private void logExpandedFormulas(
            List<OntologyLoader.AxiomWithLabel> expandedAxioms) {
        PipelineLogger.log("\n=== EXPANDED FORMULAS (XML) ===\n");
        expandedAxioms.forEach(a ->
                PipelineLogger.log(a.standpointLabels.get(0)));
        PipelineLogger.log("");

        PipelineLogger.log("\n=== EXPANDED FORMULAS (READABLE) ===\n");
        expandedAxioms.forEach(a ->
                PipelineLogger.log(formatFormula(a.standpointLabels.get(0))));
        PipelineLogger.log("");
    }

    private void printResults(Map<String, ModalPlaceholder> placeholderMap,
                              List<Sharpening> sharpenings) {
        PipelineLogger.result("\n=== FULL NORMALISED PLACEHOLDER MAP ===\n");
        placeholderMap.forEach((k, v) ->
                PipelineLogger.result(k + " → " + v));

        PipelineLogger.result("\n=== SHARPENINGS ===\n");
        if (sharpenings.isEmpty()) {
            PipelineLogger.result("(none)");
        } else {
            sharpenings.forEach(s -> PipelineLogger.result(s.toString()));
        }
        PipelineLogger.result("\n======================================\n\n");
    }

    private String extractInnerContent(String xml) {
        try {
            Document doc = parseXml(xml);
            Node axiom = doc.getDocumentElement().getFirstChild();
            StringBuilder sb = new StringBuilder();
            NodeList children = axiom.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    sb.append(child.getTextContent());
                } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                    sb.append(nodeToString(child));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid axiom label XML: " + e.getMessage(), e);
        }
    }

    private String formatFormula(String xml) {
        try {
            Document doc = parseXml(xml);
            return formatNode(doc.getDocumentElement().getFirstChild());
        } catch (Exception e) {
            return xml;
        }
    }

    private String formatAxiomLabel(String xml) {
        try {
            Document doc = parseXml(xml);
            Node axiom = doc.getDocumentElement().getFirstChild();
            StringBuilder sb = new StringBuilder();
            NodeList children = axiom.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    sb.append(child.getTextContent().trim());
                } else if (child.getNodeType() == Node.ELEMENT_NODE
                        && child.getNodeName().equals("modal")) {
                    sb.append(formatNode(child));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return xml;
        }
    }

    private String formatNode(Node node) {
        if (node == null) return "";
        if (node.getNodeType() == Node.TEXT_NODE)
            return node.getTextContent().trim();

        String op         = node.getAttributes().getNamedItem("op").getNodeValue();
        String standpoint = node.getAttributes().getNamedItem("standpoint").getNodeValue();
        Node negated      = node.getAttributes().getNamedItem("negated");
        Node negatedInner = node.getAttributes().getNamedItem("negatedInner");
        String symbol     = "box".equals(op) ? "□" : "◇";

        StringBuilder inner = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                inner.append(child.getTextContent().trim());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                inner.append(formatNode(child));
            }
        }

        if (negatedInner != null && "true".equals(negatedInner.getNodeValue()))
            return symbol + "_" + standpoint + " [ ¬ ( "
                    + inner.toString().trim() + " ) ] ";
        if (negated != null && "true".equals(negated.getNodeValue()))
            return "¬" + symbol + "_" + standpoint + " [ "
                    + inner.toString().trim() + " ] ";
        return symbol + "_" + standpoint + " [ " + inner.toString().trim() + " ] ";
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(
                new InputSource(new StringReader("<root>" + xml.trim() + "</root>")));
    }

    private String nodeToString(Node node) throws Exception {
        javax.xml.transform.TransformerFactory tf =
                javax.xml.transform.TransformerFactory.newInstance();
        javax.xml.transform.Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(
                javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
        java.io.StringWriter writer = new java.io.StringWriter();
        transformer.transform(
                new javax.xml.transform.dom.DOMSource(node),
                new javax.xml.transform.stream.StreamResult(writer));
        return writer.toString();
    }
}