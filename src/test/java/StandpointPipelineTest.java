import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.Assert;
import org.junit.runners.MethodSorters;
import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.standpoint.plugin.model.PlaceholderType;
import org.standpoint.plugin.model.StandpointAxiomType;
import org.standpoint.plugin.pipeline.NormalisationPipeline;
import org.standpoint.plugin.pipeline.PrecisificationPipeline;
import org.standpoint.plugin.pipeline.TranslationPipeline;
import org.standpoint.plugin.pipeline.data.NormalisedAxiom;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.normalisation.AnnotationProcessor;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

/**
 * Integration test suite for the four-pipeline Standpoint-SHIQ system.
 *
 * ALL shared data (axioms, formulas, standpoints, sharpenings) is generated
 * ONCE in {@code @BeforeClass} and reused by every test. Tests only differ
 * in which pipelines they run and what they assert.
 *
 *   test01NormalisationPipeline   — P1: owlMap root count + sharpening count
 *   test02PrecisificationPipeline — P2: |Π_K| = S + 2·D + I·D
 *   test03TranslationPipeline     — P3: Σ |σ(na.standpoint)| over owlMap
 *   test04ConsistencyWithHermit   — P4: HermiT consistency check (adaptive)
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class StandpointPipelineTest {

    // =========================================================================
    // Shared KB data — generated once, reused by all four tests
    // =========================================================================

    private static List<GeneratedAxiom>    sharedAxioms;
    private static List<GeneratedFormula>  sharedFormulas;
    private static List<String>            sharedStandpoints;
    private static List<GeneratedSharpening> sharedSharpening;

    /**
     * Generates the complete KB once before any test runs using a temporary
     * instance. All four tests read from these static fields.
     *
     * Sharpening mix (3, 3, 3): exercises Rules (8) and (9) in Pipeline 1,
     * and provides the basis for the adaptive consistency assertion in test 4.
     */
    @BeforeClass
    public static void setupSharedData() {
        StandpointPipelineTest gen = new StandpointPipelineTest();

        sharedAxioms = new ArrayList<>();
        for (int i = 0; i < 2; i++) sharedAxioms.add(gen.generateCI());
        for (int i = 0; i < 2; i++) sharedAxioms.add(gen.generateCA());
        for (int i = 0; i < 2; i++) sharedAxioms.add(gen.generateRI());
        for (int i = 0; i < 2; i++) sharedAxioms.add(gen.generateRA());
        for (int i = 0; i < 2; i++) sharedAxioms.add(gen.generateRT());
        for (int i = 0; i < 2; i++) sharedAxioms.add(gen.generateNestedCI());

        sharedFormulas    = gen.generateFormulas(sharedAxioms, 2);
        sharedStandpoints = gen.extractStandpoints(sharedFormulas);
        sharedSharpening = gen.generateSharpening(sharedStandpoints, 5);
    }

    // ── Instance counters — used only by the @BeforeClass generator ──────────

    private int conceptCounter    = 0;
    private int roleCounter       = 0;
    private int indCounter        = 0;
    private int standpointCounter = 0;
    private int axiomIdCounter    = 0;

    private String freshConcept()    { return "C"   + (++conceptCounter); }
    private String freshRole()       { return "r"   + (++roleCounter); }
    private String freshIndividual() { return "ind" + (++indCounter); }
    private String freshStandpoint() { return "s"   + (++standpointCounter); }
    private String freshAxiomId()    { return "F"   + (++axiomIdCounter); }

    // =========================================================================
    // PIPELINE 1 — Normalisation
    // =========================================================================

    /**
     * Verifies that Pipeline 1 produces the correct number of root owlMap
     * entries and sharpening statements, predicted analytically from the
     * normalisation rules applied to the shared KB.
     */
    @Test
    public void test01NormalisationPipeline() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.ON);

        ExpectedNormalisationCounts expected =
                computeExpectedNormalisation(sharedAxioms, sharedFormulas, sharedSharpening);

        OWLOntology ontology = buildOntology(sharedAxioms, sharedFormulas, sharedSharpening);
        StandpointKnowledgeBase kb = new AnnotationProcessor(ontology).run();

        long actualRoots = kb.owlMap.values().stream()
                .filter(na -> na.isRoot).count();

        System.out.println("\n=== PIPELINE 1 — NORMALISATION TEST ===");
        System.out.printf("Root axioms:  expected=%-4d actual=%d%n",
                expected.axiomCount, actualRoots);
        System.out.printf("Sharpenings:  expected=%-4d actual=%d%n",
                expected.sharpeningCount, kb.sharpening.size());
        System.out.printf("Fresh FC:     expected=%-4d actual=%d%n",
                expected.freshConceptCount, countFreshConcepts(kb));
        System.out.printf("Fresh FR:     expected=%-4d actual=%d%n",
                expected.freshRoleCount, countFreshRoles(kb));

        Assert.assertEquals("Axiom count mismatch",
                expected.axiomCount, (int) actualRoots);
        Assert.assertEquals("Sharpening count mismatch",
                expected.sharpeningCount, kb.sharpening.size());
    }

    // =========================================================================
    // PIPELINE 2 — Precisification
    // =========================================================================

    /**
     * Verifies that Pipeline 2 builds the correct precisification set:
     *
     *   |Π_K| = S + 2·D + I·D
     *
     * S, D, I are read from ctx and kb — nothing is hardcoded.
     */
    @Test
    public void test02PrecisificationPipeline() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);

        OWLOntology ontology = buildOntology(sharedAxioms, sharedFormulas, sharedSharpening);
        StandpointKnowledgeBase kb  = new NormalisationPipeline(ontology).run();
        PrecisificationContext  ctx = new PrecisificationPipeline(kb).run();

        int S = ctx.standpoints.size();
        int D = ctx.diamonds.size();
        int I = collectIndividualCount(kb);
        int expectedPrecSet = S + 2 * D + I * D;

        System.out.println("\n=== PIPELINE 2 — PRECISIFICATION TEST ===");
        System.out.printf("Standpoints S:        %d%n", S);
        System.out.printf("Diamond subterms D:   %d%n", D);
        System.out.printf("Individuals I:        %d%n", I);
        System.out.printf("|Π_K|: expected=%-4d actual=%d%n",
                expectedPrecSet, ctx.precSet.size());

        Assert.assertEquals("Precisification set size mismatch",
                expectedPrecSet, ctx.precSet.size());
    }

    // =========================================================================
    // PIPELINE 3 — Translation
    // =========================================================================

    /**
     * Verifies that Pipeline 3 produces the correct number of logical axioms:
     *
     *   total = Σ (for every na in kb.owlMap) |σ(na.standpoint)|
     *
     * Non-root entries → AUX definitions (Type 1).
     * Root entries     → translated axioms (Types 2–6).
     */
    @Test
    public void test03TranslationPipeline() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);

        OWLOntology ontology = buildOntology(sharedAxioms, sharedFormulas, sharedSharpening);
        StandpointKnowledgeBase kb  = new NormalisationPipeline(ontology).run();
        PrecisificationContext  ctx = new PrecisificationPipeline(kb).run();
        OWLOntology translated      = new TranslationPipeline(kb, ctx, null).run();

        int expectedTotal = computeExpectedTranslationAxiomCount(kb, ctx);
        int actualAxioms  = translated.getLogicalAxiomCount();

        System.out.println("\n=== PIPELINE 3 — TRANSLATION TEST ===");
        System.out.printf("Expected total axioms: %-4d actual=%d%n",
                expectedTotal, actualAxioms);

        Assert.assertEquals("Translated axiom count mismatch",
                expectedTotal, actualAxioms);
    }

// =========================================================================
// PIPELINE 4 — HermiT Consistency Check
// =========================================================================

    /**
     * Tests semantic consistency of the translated ontology using HermiT.
     *
     * Both modes use the same shared axioms and formulas with NORMAL
     * sharpenings only (guaranteed consistent base).
     *
     * Mode A (consistent): no contradiction — normal sharpenings only.
     *   Fresh concept/role names, no conflicting constraints.
     *
     * Mode B (inconsistent): same KB + two unlabelled contradiction axioms:
     *   ContraC ⊑ ⊥           (unlabelled → wrapped as □_*[ContraC ⊑ ⊥])
     *   ContraC(contra_ind)    (unlabelled → wrapped as □_*[ContraC(contra_ind)])
     *
     * After translation with the corrected σ:
     *   σ(*) = Π_K  (all worlds, since * ∈ t^K for every standpoint t)
     *   → ContraC_π ⊑ ⊥          for every π ∈ Π_K
     *   → ContraC_π(contra_ind)   for every π ∈ Π_K
     *   → contra_ind ∈ ContraC_π = ∅  → guaranteed inconsistent every run.
     */
    @Test
    public void test04ConsistencyWithHermit() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.ON);

        System.out.println("\n=== PIPELINE 4 — HERMIT CONSISTENCY TEST ===");
        // ── Mode A: consistent ────────────────────────────────────────────────
        OWLOntology ontologyA        = buildOntology(sharedAxioms, sharedFormulas, sharedSharpening);
        StandpointKnowledgeBase kb1  = new NormalisationPipeline(ontologyA).run();
        PrecisificationContext  ctx1 = new PrecisificationPipeline(kb1).run();
        OWLOntology translated1      = new TranslationPipeline(kb1, ctx1, null).run();

        OWLReasoner reasoner1 = new Reasoner(new Configuration(), translated1);
        boolean modeAResult   = reasoner1.isConsistent();
        reasoner1.dispose();

        System.out.printf("Mode A (no contradiction):   consistent = %b  (expected: true)%n", modeAResult);
        Assert.assertTrue("Mode A: expected consistent KB", modeAResult);

        // ── Mode B: direct contradiction via □_*[ContraC ⊑ ⊥] + □_*[ContraC(ind)] ──
        OWLOntology ontologyB        = buildOntology(sharedAxioms, sharedFormulas, sharedSharpening);
        injectContradiction(ontologyB);

        StandpointKnowledgeBase kb2  = new NormalisationPipeline(ontologyB).run();
        PrecisificationContext  ctx2 = new PrecisificationPipeline(kb2).run();
        OWLOntology translated2      = new TranslationPipeline(kb2, ctx2, null).run();

        OWLReasoner reasoner2 = new Reasoner(new Configuration(), translated2);
        boolean modeBResult   = reasoner2.isConsistent();
        reasoner2.dispose();

        System.out.printf("Mode B (contradiction injected): consistent = %b  (expected: false)%n",
                modeBResult);
        Assert.assertFalse("Mode B: expected inconsistent KB", modeBResult);
    }

    /**
     * Adds two unlabelled OWL axioms to the ontology.
     * Unlabelled axioms are picked up by injectUnlabelledAxioms and wrapped
     * as □_*[...], so they are translated for every π ∈ σ(*) = Π_K.
     *
     *   ContraC ⊑ ⊥          →  □_*[ContraC ⊑ ⊥]   →  ContraC_π ⊑ ⊥ for all π
     *   ContraC(contra_ind)   →  □_*[ContraC(ind)]   →  ContraC_π(ind) for all π
     *
     * The two together make every world contain contra_ind ∈ ContraC_π = ∅.
     * Deterministic — no randomness involved.
     */
    private void injectContradiction(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory     df      = manager.getOWLDataFactory();
        String base = "http://standpoint.org/test#";

        OWLClass           contraC   = df.getOWLClass(IRI.create(base + "ContraC"));
        OWLNamedIndividual contraInd =
                df.getOWLNamedIndividual(IRI.create(base + "contra_ind"));

        // No standpointAxiom annotation → picked up as unlabelled → wrapped as □_*
        manager.addAxiom(ontology,
                df.getOWLSubClassOfAxiom(contraC, df.getOWLNothing()));
        manager.addAxiom(ontology,
                df.getOWLClassAssertionAxiom(contraC, contraInd));
    }

    // =========================================================================
    // Expected count helpers
    // =========================================================================

    private int computeExpectedTranslationAxiomCount(
            StandpointKnowledgeBase kb,
            PrecisificationContext ctx) {
        int total = 0;
        for (NormalisedAxiom na : kb.owlMap.values())
            total += ctx.precSet.sigma(na.standpoint).size();
        return total;
    }

    private int collectIndividualCount(StandpointKnowledgeBase kb) {
        Set<OWLNamedIndividual> found = new HashSet<>();
        for (NormalisedAxiom na : kb.owlMap.values()) {
            if (na.owlAxiom != null)
                found.addAll(na.owlAxiom.getIndividualsInSignature());
            if (na.owlTree != null)
                found.addAll(na.owlTree.getIndividualsInSignature());
        }
        return found.size();
    }

    // =========================================================================
    // GeneratedAxiom
    // =========================================================================

    private static class GeneratedAxiom {
        public final String            id;
        public final StandpointAxiomType kind;
        public final boolean           negated;
        public final String            nameA, nameB, nameC;
        public final String            modalOp, modalStandpoint;
        public final boolean           modalInnerNeg;

        public GeneratedAxiom(String id, StandpointAxiomType kind, boolean negated,
                              String nameA, String nameB, String nameC,
                              String modalOp, String modalStandpoint,
                              boolean modalInnerNeg) {
            this.id              = id;
            this.kind            = kind;
            this.negated         = negated;
            this.nameA           = nameA;
            this.nameB           = nameB;
            this.nameC           = nameC;
            this.modalOp         = modalOp;
            this.modalStandpoint = modalStandpoint;
            this.modalInnerNeg   = modalInnerNeg;
        }

        public boolean hasModal() { return modalOp != null; }
    }

    // ── Axiom generators ─────────────────────────────────────────────────────

    private GeneratedAxiom generateCI() {
        return new GeneratedAxiom(freshAxiomId(),
                StandpointAxiomType.CONCEPT_INCLUSION, new Random().nextBoolean(),
                freshConcept(), freshConcept(), null, null, null, false);
    }

    private GeneratedAxiom generateNestedCI() {
        Random rand = new Random();
        return new GeneratedAxiom(freshAxiomId(),
                StandpointAxiomType.CONCEPT_INCLUSION, rand.nextBoolean(),
                freshConcept(), freshConcept(), null,
                rand.nextBoolean() ? "box" : "diamond",
                freshStandpoint(), rand.nextBoolean());
    }

    private GeneratedAxiom generateCA() {
        return new GeneratedAxiom(freshAxiomId(),
                StandpointAxiomType.CONCEPT_ASSERTION, new Random().nextBoolean(),
                freshIndividual(), freshConcept(), null, null, null, false);
    }

    private GeneratedAxiom generateRI() {
        return new GeneratedAxiom(freshAxiomId(),
                StandpointAxiomType.ROLE_INCLUSION, new Random().nextBoolean(),
                freshRole(), freshRole(), null, null, null, false);
    }

    private GeneratedAxiom generateRA() {
        return new GeneratedAxiom(freshAxiomId(),
                StandpointAxiomType.ROLE_ASSERTION, new Random().nextBoolean(),
                freshIndividual(), freshRole(), freshIndividual(),
                null, null, false);
    }

    private GeneratedAxiom generateRT() {
        return new GeneratedAxiom(freshAxiomId(),
                StandpointAxiomType.ROLE_TRANSITIVITY, new Random().nextBoolean(),
                freshRole(), null, null, null, null, false);
    }

    // ── OWL axiom builder ─────────────────────────────────────────────────────

    private OWLAxiom buildOWLAxiom(GeneratedAxiom ax, OWLDataFactory df, String base) {
        switch (ax.kind) {
            case CONCEPT_INCLUSION:
                return df.getOWLSubClassOfAxiom(
                        df.getOWLClass(IRI.create(base + ax.nameA)),
                        df.getOWLClass(IRI.create(base + ax.nameB)));
            case CONCEPT_ASSERTION:
                return df.getOWLClassAssertionAxiom(
                        df.getOWLClass(IRI.create(base + ax.nameB)),
                        df.getOWLNamedIndividual(IRI.create(base + ax.nameA)));
            case ROLE_INCLUSION:
                return df.getOWLSubObjectPropertyOfAxiom(
                        df.getOWLObjectProperty(IRI.create(base + ax.nameA)),
                        df.getOWLObjectProperty(IRI.create(base + ax.nameB)));
            case ROLE_ASSERTION:
                return df.getOWLObjectPropertyAssertionAxiom(
                        df.getOWLObjectProperty(IRI.create(base + ax.nameB)),
                        df.getOWLNamedIndividual(IRI.create(base + ax.nameA)),
                        df.getOWLNamedIndividual(IRI.create(base + ax.nameC)));
            case ROLE_TRANSITIVITY:
                return df.getOWLTransitiveObjectPropertyAxiom(
                        df.getOWLObjectProperty(IRI.create(base + ax.nameA)));
            default: throw new IllegalArgumentException("Unknown kind: " + ax.kind);
        }
    }

    // ── Annotation string builder ─────────────────────────────────────────────

    private String buildAxiomAnnotation(GeneratedAxiom ax) {
        StringBuilder body = new StringBuilder();
        switch (ax.kind) {
            case CONCEPT_INCLUSION:
                if (ax.hasModal()) {
                    String inner = ax.modalInnerNeg
                            ? " not ( " + ax.nameA + " ) " : ax.nameA;
                    body.append("<modal op=\"").append(ax.modalOp)
                            .append("\" standpoint=\"").append(ax.modalStandpoint)
                            .append("\">").append(inner).append("</modal>")
                            .append(" SubClassOf: ").append(ax.nameB);
                } else {
                    body.append(ax.nameA).append(" SubClassOf: ").append(ax.nameB);
                }
                break;
            case CONCEPT_ASSERTION:
                body.append(ax.nameA).append(" Type: ").append(ax.nameB);
                break;
            case ROLE_INCLUSION:
                body.append(ax.nameA).append(" SubPropertyOf: ").append(ax.nameB);
                break;
            case ROLE_ASSERTION:
                body.append("Individual: ").append(ax.nameA)
                        .append(" Facts: ").append(ax.nameB)
                        .append(" ").append(ax.nameC);
                break;
            case ROLE_TRANSITIVITY:
                body.append("Transitive ").append(ax.nameA);
                break;
        }
        return "<axiom id=\"" + ax.id + "\">" + body + "</axiom>";
    }

    // =========================================================================
    // Shared infrastructure
    // =========================================================================

    private List<String> extractStandpoints(List<GeneratedFormula> formulas) {
        List<String> out = new ArrayList<>();
        for (GeneratedFormula f : formulas) out.add(f.standpoint);
        return out;
    }

    private int countFreshConcepts(StandpointKnowledgeBase result) {
        Set<String> found = new HashSet<>();
        for (NormalisedAxiom na : result.owlMap.values()) {
            Set<OWLClass> classes = na.isRoot && na.owlAxiom != null
                    ? na.owlAxiom.getClassesInSignature()
                    : na.owlTree != null
                    ? na.owlTree.getClassesInSignature()
                    : Collections.emptySet();
            for (OWLClass cls : classes) {
                String s = cls.getIRI().getShortForm();
                if (s.startsWith(PlaceholderType.FRESH_CONCEPT.prefix)) found.add(s);
            }
        }
        return found.size();
    }

    private int countFreshRoles(StandpointKnowledgeBase result) {
        Set<String> found = new HashSet<>();
        for (NormalisedAxiom na : result.owlMap.values()) {
            if (na.owlAxiom == null) continue;
            for (OWLObjectProperty p : na.owlAxiom.getObjectPropertiesInSignature()) {
                String s = p.getIRI().getShortForm();
                if (s.startsWith(PlaceholderType.FRESH_ROLE.prefix)) found.add(s);
            }
        }
        return found.size();
    }

    // ── Formula / sharpening generators ──────────────────────────────────────

    private static class GeneratedFormula {
        public final String operator, standpoint;
        public final List<GeneratedAxiom> literals;

        public GeneratedFormula(String operator, String standpoint,
                                List<GeneratedAxiom> literals) {
            this.operator   = operator;
            this.standpoint = standpoint;
            this.literals   = literals;
        }
    }

    private List<GeneratedFormula> generateFormulas(
            List<GeneratedAxiom> axioms, int numFormulas) {
        List<GeneratedFormula> formulas = new ArrayList<>();
        Random rand = new Random();
        List<GeneratedAxiom> shuffled = new ArrayList<>(axioms);
        Collections.shuffle(shuffled);
        int idx = 0;
        for (int i = 0; i < numFormulas && idx < shuffled.size(); i++) {
            String op = rand.nextBoolean() ? "box" : "diamond";
            String sp = freshStandpoint();
            int remaining = shuffled.size() - idx;
            int remainF   = numFormulas - i;
            int maxCount  = remaining - (remainF - 1);
            int count     = maxCount <= 1 ? 1 : 1 + rand.nextInt(maxCount);
            List<GeneratedAxiom> lits = new ArrayList<>();
            for (int j = 0; j < count && idx < shuffled.size(); j++, idx++)
                lits.add(shuffled.get(idx));
            formulas.add(new GeneratedFormula(op, sp, lits));
        }
        return formulas;
    }

    private enum SharpeningKind { NORMAL, ZERO, NEGATED }

    private static class GeneratedSharpening {
        public final List<String> lhs;
        public final String       rhs;
        public final SharpeningKind kind;

        public GeneratedSharpening(List<String> lhs, String rhs, SharpeningKind kind) {
            this.lhs  = lhs;
            this.rhs  = rhs;
            this.kind = kind;
        }
    }

    /**
     * Generates NORMAL sharpenings consuming each standpoint exactly once
     * (as either LHS or RHS). When the original pool is exhausted, fresh
     * standpoints (FS_1, FS_2, ...) are generated in pairs to fill remaining
     * sharpenings.
     *
     * With standpoints=[s1,s2], count=3:
     *   Sharpening 1: s1 ⪯ s2   → pool empty
     *   Sharpening 2: FS_1 ⪯ FS_2  → fresh pair
     *   Sharpening 3: FS_3 ⪯ FS_4  → fresh pair
     *
     * Every standpoint name appears exactly once across all sharpenings.
     */
    private List<GeneratedSharpening> generateSharpening(
            List<String> standpoints, int count) {
        List<GeneratedSharpening> result = new ArrayList<>();
        List<String> available = new ArrayList<>(standpoints);
        Collections.shuffle(available, new Random());
        int freshCounter = 0;

        for (int i = 0; i < count; i++) {
            // Top up to at least 2 using fresh standpoints if pool is exhausted
            while (available.size() < 2)
                available.add("FS_" + (++freshCounter));

            String lhs = available.remove(0);
            String rhs = available.remove(0);
            result.add(new GeneratedSharpening(
                    Collections.singletonList(lhs), rhs, SharpeningKind.NORMAL));
        }
        return result;
    }
    // ── Expected count computation (Pipeline 1) ───────────────────────────────

    private static class ExpectedNormalisationCounts {
        public int axiomCount = 0, sharpeningCount = 0,
                freshConceptCount = 0, freshRoleCount = 0;
    }

    private ExpectedNormalisationCounts computeExpectedNormalisation(
            List<GeneratedAxiom> allAxioms,
            List<GeneratedFormula> formulas,
            List<GeneratedSharpening> sharpenings) {

        ExpectedNormalisationCounts counts = new ExpectedNormalisationCounts();

        Set<String> referenced = new HashSet<>();
        for (GeneratedFormula f : formulas)
            for (GeneratedAxiom a : f.literals)
                referenced.add(a.id);

        for (GeneratedAxiom a : allAxioms)
            if (!referenced.contains(a.id))
                counts.axiomCount++;

        for (GeneratedFormula formula : formulas) {
            if ("diamond".equals(formula.operator)) counts.sharpeningCount++;
            for (GeneratedAxiom ax : formula.literals) {
                if (ax.negated) {
                    switch (ax.kind) {
                        case CONCEPT_INCLUSION:
                            counts.axiomCount += 3;
                            counts.freshConceptCount++;
                            counts.freshRoleCount++;
                            break;
                        case CONCEPT_ASSERTION:
                            counts.axiomCount++;
                            break;
                        case ROLE_INCLUSION:
                            counts.axiomCount += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount++;
                            break;
                        case ROLE_ASSERTION:
                            counts.axiomCount += 3;
                            counts.freshConceptCount += 2;
                            break;
                        case ROLE_TRANSITIVITY:
                            counts.axiomCount += 3;
                            counts.freshConceptCount += 2;
                            counts.freshRoleCount++;
                            break;
                    }
                } else {
                    counts.axiomCount++;
                }
            }
        }

        for (GeneratedSharpening s : sharpenings) {
            int n = s.lhs.size();
            switch (s.kind) {
                case NORMAL:  counts.sharpeningCount++;                  break;
                case ZERO:    counts.axiomCount += n + 1;
                    counts.freshConceptCount += n;             break;
                case NEGATED: counts.sharpeningCount += n;
                    counts.axiomCount += 3;
                    counts.freshConceptCount += 2;             break;
            }
        }
        return counts;
    }

    // ── Ontology builder ─────────────────────────────────────────────────────

    private OWLOntology buildOntology(
            List<GeneratedAxiom> axioms,
            List<GeneratedFormula> formulas,
            List<GeneratedSharpening> sharpenings) throws Exception {

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
                IRI.create("http://standpoint.org/test"));
        String base = "http://standpoint.org/test#";

        OWLAnnotationProperty axiomProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointFormula"));
        OWLAnnotationProperty sharpeningProp = df.getOWLAnnotationProperty(
                IRI.create(base + "standpointSharpening"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(axiomProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(formulaProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(sharpeningProp));

        for (GeneratedAxiom ax : axioms)
            manager.addAxiom(ontology,
                    buildOWLAxiom(ax, df, base).getAnnotatedAxiom(
                            Collections.singleton(df.getOWLAnnotation(axiomProp,
                                    df.getOWLLiteral(buildAxiomAnnotation(ax))))));

        for (GeneratedFormula f : formulas)
            manager.applyChange(new AddOntologyAnnotation(ontology,
                    df.getOWLAnnotation(formulaProp,
                            df.getOWLLiteral(buildFormulaXml(f)))));

        for (GeneratedSharpening s : sharpenings)
            manager.applyChange(new AddOntologyAnnotation(ontology,
                    df.getOWLAnnotation(sharpeningProp,
                            df.getOWLLiteral(buildSharpeningXml(s)))));

        return ontology;
    }

    private String buildFormulaXml(GeneratedFormula formula) {
        StringBuilder sb = new StringBuilder();
        sb.append("<formula op=\"").append(formula.operator)
                .append("\" standpoint=\"").append(formula.standpoint).append("\">");
        if (formula.literals.size() == 1) {
            GeneratedAxiom lit = formula.literals.get(0);
            sb.append("<literal ref=\"").append(lit.id).append("\"");
            if (lit.negated) sb.append(" negated=\"true\"");
            sb.append("/>");
        } else {
            sb.append("<intersection>");
            for (GeneratedAxiom lit : formula.literals) {
                sb.append("<literal ref=\"").append(lit.id).append("\"");
                if (lit.negated) sb.append(" negated=\"true\"");
                sb.append("/>");
            }
            sb.append("</intersection>");
        }
        sb.append("</formula>");
        return sb.toString();
    }

    private String buildSharpeningXml(GeneratedSharpening s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.kind == SharpeningKind.NEGATED
                ? "<sharpening negated=\"true\">" : "<sharpening>");
        sb.append("<lhs>");
        if (s.lhs.size() == 1)
            sb.append("<standpoint>").append(s.lhs.get(0)).append("</standpoint>");
        else {
            sb.append("<intersection>");
            for (String sp : s.lhs)
                sb.append("<standpoint>").append(sp).append("</standpoint>");
            sb.append("</intersection>");
        }
        sb.append("</lhs><rhs>");
        if ("0".equals(s.rhs)) sb.append("<zero/>");
        else sb.append("<standpoint>").append(s.rhs).append("</standpoint>");
        sb.append("</rhs></sharpening>");
        return sb.toString();
    }
}
