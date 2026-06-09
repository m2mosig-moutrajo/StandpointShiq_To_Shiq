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
     * Sharpening mix (3 NORMAL + 1 ZERO + 1 NEGATED): exercises Rules (8) and (9)
     * in Pipeline 1. ZERO/NEGATED entries guarantee P4 Mode B inconsistency.
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
        sharedSharpening  = gen.generateSharpening(sharedStandpoints, 3, 1, 1);
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
     * P1: verifies root owlMap count and sharpening count on the shared KB.
     * Delegates to runPipelineAssertions — all assertion logic lives there.
     */
    @Test
    public void test01NormalisationPipeline() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        runPipelineAssertions(sharedAxioms, sharedFormulas, sharedSharpening);
    }

    // =========================================================================
    // PIPELINE 2 — Precisification
    // =========================================================================

    /**
     * P2: verifies |Π_K| = S + 2·D + I·D on the shared KB.
     * Delegates to runPipelineAssertions — all assertion logic lives there.
     */
    @Test
    public void test02PrecisificationPipeline() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        runPipelineAssertions(sharedAxioms, sharedFormulas, sharedSharpening);
    }

    // =========================================================================
    // PIPELINE 3 — Translation
    // =========================================================================

    /**
     * P3: verifies translated logical axiom count = Σ |σ(na.standpoint)|
     * over every owlMap entry on the shared KB.
     * Delegates to runPipelineAssertions — all assertion logic lives there.
     */
    @Test
    public void test03TranslationPipeline() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        runPipelineAssertions(sharedAxioms, sharedFormulas, sharedSharpening);
    }

// =========================================================================
// PIPELINE 4 — HermiT Consistency Check
// =========================================================================

    /**
     * P4: HermiT consistency check on the shared KB.
     *
     * The shared KB contains ZERO and NEGATED sharpenings, so even Mode A
     * (no extra contradiction injected) is expected to be inconsistent here.
     * Mode B adds injectContradiction on top, which is also inconsistent.
     *
     * Both modes assert inconsistent because:
     *   - ZERO sharpening (s ⪯ 0): Rule (9) → □_s[⊤ ⊑ FC] + □_*[FC ⊑ ⊥]
     *     → after σ fix (σ(*) = Π_K) → ⊤ ⊑ ⊥ guaranteed.
     *   - NEGATED sharpening ¬(s ⪯ t): Rule (8) → internal ZERO → same result.
     *
     * Delegates to runPipelineAssertions — all assertion logic lives there.
     */
    @Test
    public void test04ConsistencyWithHermit() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        runPipelineAssertions(sharedAxioms, sharedFormulas, sharedSharpening);
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
    // Extra coverage — parameterized via runTest
    // =========================================================================

    /**
     * Smoke test on a minimal KB: 1 of each axiom kind, 1 formula,
     * 2 NORMAL sharpenings — no ZERO or NEGATED, so Mode A is consistent.
     */
    @Test
    public void testMinimalKB() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.ON);
        runTest(1, 1, 1, 1, 1, 1, 2, 2, 0, 0);
    }

    /**
     * Stress test on a larger KB: 5 of each axiom kind, 4 formulas,
     * 4 NORMAL + 1 ZERO + 1 NEGATED sharpenings.
     * ZERO/NEGATED guarantee inconsistency via Rules (9) and (8).
     */
    @Test
    public void testLargerKB() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        runTest(5, 5, 5, 5, 5, 5, 4, 4, 10, 10);
    }

    // =========================================================================
    // Flexible full-pipeline runner
    // =========================================================================

    /**
     * Generates a fresh, independent KB from explicit parameters, resets all
     * instance counters so the run is isolated from shared data, then delegates
     * to {@link #runPipelineAssertions}.
     *
     * @param numCI       concept inclusions (SubClassOf)
     * @param numCA       concept assertions (Type:)
     * @param numRI       role inclusions (SubPropertyOf)
     * @param numRA       role assertions (Individual: Facts:)
     * @param numRT       transitivity axioms
     * @param numNested   nested modal concept inclusions
     * @param numFormulas standpoint formulas
     * @param numNormal   NORMAL sharpenings — no forced inconsistency
     * @param numZero     ZERO sharpenings — Rule (9) → ⊤ ⊑ ⊥
     * @param numNegated  NEGATED sharpenings — Rule (8) → internal zero → ⊤ ⊑ ⊥
     */
    private void runTest(
            int numCI, int numCA, int numRI, int numRA, int numRT,
            int numNested, int numFormulas,
            int numNormal, int numZero, int numNegated) throws Exception {

        // Reset counters — fully independent of shared data
        conceptCounter = roleCounter = indCounter
                = standpointCounter = axiomIdCounter = 0;

        List<GeneratedAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < numCI;     i++) axioms.add(generateCI());
        for (int i = 0; i < numCA;     i++) axioms.add(generateCA());
        for (int i = 0; i < numRI;     i++) axioms.add(generateRI());
        for (int i = 0; i < numRA;     i++) axioms.add(generateRA());
        for (int i = 0; i < numRT;     i++) axioms.add(generateRT());
        for (int i = 0; i < numNested; i++) axioms.add(generateNestedCI());

        List<GeneratedFormula>    formulas    = generateFormulas(axioms, numFormulas);
        List<String>              standpoints = extractStandpoints(formulas);
        List<GeneratedSharpening> sharpening  = generateSharpening(standpoints, numNormal, numZero, numNegated);

        runPipelineAssertions(axioms, formulas, sharpening);
    }

    /**
     * Runs all four pipeline assertions (P1, P2, P3, P4 Modes A+B) on the
     * given KB data. Shared by both {@link #runTest} and the named {@code @Test}
     * methods — keeping assertion logic here ensures both paths test identically.
     *
     * P1 uses AnnotationProcessor directly (Pipeline 1 entry point).
     * P2 + P3 share one normalisation chain; P4 Mode A reuses P3's translated
     * ontology. P4 Mode B builds a separate ontology with injectContradiction.
     *
     * If sharpening contains ZERO or NEGATED entries, the KB is already
     * inconsistent before Mode B injection — both Mode A and Mode B will
     * assert false, which is correct behaviour.
     */
    private void runPipelineAssertions(
            List<GeneratedAxiom>      axioms,
            List<GeneratedFormula>    formulas,
            List<GeneratedSharpening> sharpening) throws Exception {

        boolean expectInconsistent = sharpening.stream()
                .anyMatch(s -> s.kind == SharpeningKind.ZERO
                        || s.kind == SharpeningKind.NEGATED);

        // ── P1 — Normalisation ────────────────────────────────────────────────
        ExpectedNormalisationCounts expected =
                computeExpectedNormalisation(axioms, formulas, sharpening);
        OWLOntology ont1 = buildOntology(axioms, formulas, sharpening);
        StandpointKnowledgeBase kb1 = new AnnotationProcessor(ont1).run();
        long actualRoots = kb1.owlMap.values().stream()
                .filter(na -> na.isRoot).count();

        System.out.println("\n=== PIPELINE 1 — NORMALISATION ===");
        System.out.printf("Root axioms:  expected=%-4d actual=%d%n",
                expected.axiomCount, actualRoots);
        System.out.printf("Sharpenings:  expected=%-4d actual=%d%n",
                expected.sharpeningCount, kb1.sharpening.size());
        System.out.printf("Fresh FC:     expected=%-4d actual=%d%n",
                expected.freshConceptCount, countFreshConcepts(kb1));
        System.out.printf("Fresh FR:     expected=%-4d actual=%d%n",
                expected.freshRoleCount, countFreshRoles(kb1));
        Assert.assertEquals("P1 axiom count",
                expected.axiomCount, (int) actualRoots);
        Assert.assertEquals("P1 sharpening count",
                expected.sharpeningCount, kb1.sharpening.size());

        // ── P2 + P3 — share the same normalisation run ───────────────────────
        OWLOntology ont2 = buildOntology(axioms, formulas, sharpening);
        StandpointKnowledgeBase kb2        = new NormalisationPipeline(ont2).run();
        PrecisificationContext  ctx        = new PrecisificationPipeline(kb2).run();
        OWLOntology             translated = new TranslationPipeline(kb2, ctx, null).run();

        // ── P2 — Precisification ──────────────────────────────────────────────
        int S = ctx.standpoints.size();
        int D = ctx.diamonds.size();
        int I = collectIndividualCount(kb2);
        int expectedPrec = S + 2 * D + I * D;

        System.out.println("\n=== PIPELINE 2 — PRECISIFICATION ===");
        System.out.printf("S=%-3d D=%-3d I=%-3d  |Π_K|: expected=%-4d actual=%d%n",
                S, D, I, expectedPrec, ctx.precSet.size());
        Assert.assertEquals("P2 precisification size", expectedPrec, ctx.precSet.size());

        // ── P3 — Translation ──────────────────────────────────────────────────
        int expectedAxioms = computeExpectedTranslationAxiomCount(kb2, ctx);
        int actualAxioms   = translated.getLogicalAxiomCount();

        System.out.println("\n=== PIPELINE 3 — TRANSLATION ===");
        System.out.printf("Expected=%-4d actual=%d%n", expectedAxioms, actualAxioms);
        Assert.assertEquals("P3 axiom count", expectedAxioms, actualAxioms);

        // ── P4 Mode A ─────────────────────────────────────────────────────────
        // If ZERO/NEGATED sharpenings are present, already inconsistent.
        OWLReasoner r1 = new Reasoner(new Configuration(), translated);
        boolean modeA  = r1.isConsistent();
        r1.dispose();

        System.out.println("\n=== PIPELINE 4 — HERMIT ===");
        System.out.printf("ZERO/NEGATED present: %b%n", expectInconsistent);
        System.out.printf("Mode A: consistent = %b  (expected: %b)%n",
                modeA, !expectInconsistent);
        if (expectInconsistent)
            Assert.assertFalse("P4 Mode A: expected inconsistent (ZERO/NEGATED present)", modeA);
        else
            Assert.assertTrue("P4 Mode A: expected consistent", modeA);

        // ── P4 Mode B — contradiction injected ───────────────────────────────
        OWLOntology ont3 = buildOntology(axioms, formulas, sharpening);
        injectContradiction(ont3);
        StandpointKnowledgeBase kb3  = new NormalisationPipeline(ont3).run();
        PrecisificationContext  ctx3 = new PrecisificationPipeline(kb3).run();
        OWLOntology translated3      = new TranslationPipeline(kb3, ctx3, null).run();

        OWLReasoner r2 = new Reasoner(new Configuration(), translated3);
        boolean modeB  = r2.isConsistent();
        r2.dispose();

        System.out.printf("Mode B: consistent = %b  (expected: false)%n", modeB);
        Assert.assertFalse("P4 Mode B: expected inconsistent", modeB);
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
     * Generates a mix of NORMAL, ZERO, and NEGATED sharpenings from a shared pool.
     *
     * NORMAL (s ⪯ t): consumes two standpoints. Consistent — contributes only
     * +1 to sharpeningCount and affects closures.
     *
     * ZERO (s ⪯ 0): consumes one standpoint as LHS; RHS is the zero element.
     * Rule (9) produces □_s[⊤ ⊑ FC] + □_*[FC ⊑ ⊥]. After the σ fix
     * (σ(*) = Π_K), this translates to ⊤ ⊑ ⊥ — guaranteed inconsistency.
     *
     * NEGATED (¬(s ⪯ t)): consumes two standpoints. Rule (8) internally
     * expands to a ZERO sharpening, producing the same ⊤ ⊑ ⊥ result.
     *
     * All three kinds consume from the same pool in order: NORMAL first,
     * then ZERO, then NEGATED. When the pool is exhausted, fresh names
     * FS_1, FS_2, … are added in pairs (or singles for ZERO).
     * Every standpoint name appears exactly once across all sharpenings.
     *
     * @param standpoints original standpoints seeded into the pool
     * @param numNormal   number of NORMAL sharpenings
     * @param numZero     number of ZERO sharpenings
     * @param numNegated  number of NEGATED sharpenings
     */
    private List<GeneratedSharpening> generateSharpening(
            List<String> standpoints, int numNormal, int numZero, int numNegated) {
        List<GeneratedSharpening> result = new ArrayList<>();
        List<String> available = new ArrayList<>(standpoints);
        Collections.shuffle(available, new Random());
        int freshCounter = 0;

        // ── NORMAL: s ⪯ t ─────────────────────────────────────────────────────
        for (int i = 0; i < numNormal; i++) {
            while (available.size() < 2) available.add("FS_" + (++freshCounter));
            String lhs = available.remove(0);
            String rhs = available.remove(0);
            result.add(new GeneratedSharpening(
                    Collections.singletonList(lhs), rhs, SharpeningKind.NORMAL));
        }

        // ── ZERO: s ⪯ 0 ───────────────────────────────────────────────────────
        // Rule (9): □_s[⊤ ⊑ FC] + □_*[FC ⊑ ⊥] → σ(*) = Π_K → ⊤ ⊑ ⊥
        for (int i = 0; i < numZero; i++) {
            while (available.size() < 1) available.add("FS_" + (++freshCounter));
            String lhs = available.remove(0);
            result.add(new GeneratedSharpening(
                    Collections.singletonList(lhs), "0", SharpeningKind.ZERO));
        }

        // ── NEGATED: ¬(s ⪯ t) ────────────────────────────────────────────────
        // Rule (8) expands to internal ZERO → same ⊤ ⊑ ⊥ result
        for (int i = 0; i < numNegated; i++) {
            while (available.size() < 2) available.add("FS_" + (++freshCounter));
            String lhs = available.remove(0);
            String rhs = available.remove(0);
            result.add(new GeneratedSharpening(
                    Collections.singletonList(lhs), rhs, SharpeningKind.NEGATED));
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
