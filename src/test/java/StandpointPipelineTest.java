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
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.util.PipelineLogger;

import java.util.*;

/**
 * Integration test suite for the Standpoint-SHIQ pipeline.
 *
 * Two @Test methods, each exercising a genuinely different input configuration:
 *   testMinimalKB — small generated KB, NORMAL sharpenings only
 *   testLargerKB  — larger generated KB, mixed NORMAL/ZERO/NEGATED sharpenings
 *
 * Each test runs P1/P2/P3 count assertions followed by six P4 consistency checks:
 *
 *   Mode A-1.0: translated KB as-is (no injection)          → consistent
 *   Mode A-1.1: append □_s[C ⊑ ⊥]  +  □_t[C(a)]           → consistent (s ≠ t)
 *   Mode A-1.2: append ◇_s[C ⊑ ⊥]  +  ◇_s[C(a)]           → consistent (two fresh standpoints)
 *
 *   Mode B-1.0: append ContraC ⊑ ⊥  +  ContraC(ind) unlabelled → inconsistent
 *   Mode B-1.1: append □_s[C ⊑ ⊥]  +  □_t[C(a)]  +  t ⪯ s    → inconsistent
 *   Mode B-1.2: append ◇_s[C ⊑ ⊥]  +  □_s[C(a)]              → inconsistent
 */
public class StandpointPipelineTest {

    // ── Instance counters ─────────────────────────────────────────────────────

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
    // TEST 1 — Minimal KB (NORMAL sharpenings only)
    // =========================================================================

    /**
     * Small generated KB: 6 axioms (one of each supported type) + 2 NORMAL
     * sharpenings. All concept/role/individual names are fresh and unique.
     * Tests P1/P2/P3 count assertions plus all six P4 consistency modes.
     */
    @Test
    public void testMinimalKB() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        runTest(1, 1, 1, 1, 1, 1, 2, 2, 0, 0);
    }

    // =========================================================================
    // TEST 2 — Larger KB (mixed sharpenings)
    // =========================================================================

    /**
     * Larger generated KB: 30 axioms + mix of NORMAL, ZERO, NEGATED sharpenings.
     * ZERO uses intersection LHS (s1 ∩ s2 ⪯ 0), which does not force
     * inconsistency without a world in both sigma(s1) and sigma(s2).
     * Tests P1/P2/P3 count assertions plus all six P4 consistency modes.
     */
    @Test
    public void testLargerKB() throws Exception {
        PipelineLogger.setLevel(PipelineLogger.Level.OFF);
        runTest(5, 5, 5, 5, 5, 5, 4, 4, 1, 1);
    }

    // =========================================================================
    // Flexible full-pipeline runner
    // =========================================================================

    private void runTest(
            int numCI, int numCA, int numRI, int numRA, int numRT,
            int numNested, int numFormulas,
            int numNormal, int numZero, int numNegated) throws Exception {

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
        List<GeneratedSharpening> sharpening  =
                generateSharpening(standpoints, numNormal, numZero, numNegated);

        runPipelineAssertions(axioms, formulas, sharpening);
    }

    /**
     * Runs P1, P2, P3, and all six P4 modes on the given KB data.
     *
     * One ontology is built (baseOnt) and used throughout:
     *   P1 → P2 → P3 run as a single chain on baseOnt; results flow through.
     *   P4 A-1.0 reuses the translated ontology from P3 directly.
     *   P4 A-1.1 through B-1.2 copy baseOnt, inject, and retranslate —
     *   required because each injection changes Π_K (new standpoints or diamonds).
     */
    private void runPipelineAssertions(
            List<GeneratedAxiom>      axioms,
            List<GeneratedFormula>    formulas,
            List<GeneratedSharpening> sharpening) throws Exception {

        // ── Single ontology build ─────────────────────────────────────────────
        OWLOntology baseOnt = buildOntology(axioms, formulas, sharpening);

        // ── P1 → P2 → P3 — single timed chain, results flow through ──────────
        ExpectedNormalisationCounts expected =
                computeExpectedNormalisation(axioms, formulas, sharpening);

        long t0 = System.currentTimeMillis();
        StandpointKnowledgeBase kb  = new NormalisationPipeline(baseOnt).run();
        long t1 = System.currentTimeMillis();
        PrecisificationContext  ctx = new PrecisificationPipeline(kb).run();
        long t2 = System.currentTimeMillis();
        OWLOntology      translated = new TranslationPipeline(ctx, null).run();
        long t3 = System.currentTimeMillis();

        System.out.printf("\nPipeline runtimes: P1=%d ms  P2=%d ms  P3=%d ms  total=%d ms%n",
                t1-t0, t2-t1, t3-t2, t3-t0);

        // ── P1 assertions ─────────────────────────────────────────────────────
        long actualRoots = kb.owlMap.values().stream()
                .filter(na -> na.isRoot).count();

        System.out.println("\n=== P1 — NORMALISATION ===");
        System.out.printf("Root axioms:  expected=%-4d actual=%d%n",
                expected.axiomCount, actualRoots);
        System.out.printf("Sharpenings:  expected=%-4d actual=%d%n",
                expected.sharpeningCount, kb.sharpening.size());
        System.out.printf("Fresh FC:     expected=%-4d actual=%d%n",
                expected.freshConceptCount, countFreshConcepts(kb));
        System.out.printf("Fresh FR:     expected=%-4d actual=%d%n",
                expected.freshRoleCount, countFreshRoles(kb));

        Assert.assertEquals("P1 axiom count",
                expected.axiomCount, (int) actualRoots);
        Assert.assertEquals("P1 sharpening count",
                expected.sharpeningCount, kb.sharpening.size());

        // ── P2 assertions ─────────────────────────────────────────────────────
        int S = ctx.standpoints.size();
        int D = ctx.diamonds.size();
        int I = collectIndividualCount(kb);
        int expectedPrec = S + 2 * D + I * D;

        System.out.println("\n=== P2 — PRECISIFICATION ===");
        System.out.printf("S=%-3d D=%-3d I=%-3d  |Π_K|: expected=%-4d actual=%d%n",
                S, D, I, expectedPrec, ctx.precSet.size());
        Assert.assertEquals("P2 precisification size", expectedPrec, ctx.precSet.size());

        // ── P3 assertions ─────────────────────────────────────────────────────
        int expectedAxioms = computeExpectedTranslationAxiomCount(kb, ctx);
        int actualAxioms   = translated.getLogicalAxiomCount();

        System.out.println("\n=== P3 — TRANSLATION ===");
        System.out.printf("Expected=%-4d actual=%d%n", expectedAxioms, actualAxioms);
        Assert.assertEquals("P3 axiom count", expectedAxioms, actualAxioms);

        System.out.println("\n=== P4 — HERMIT ===");

        // ── Mode A-1.0 — reuse translated from P3 (no copy, no rebuild) ──────
        // All names are unique across generated axioms → structurally consistent.
        long tA10s = System.currentTimeMillis();
        OWLReasoner rA10 = new Reasoner(new Configuration(), translated);
        boolean modeA10  = rA10.isConsistent(); rA10.dispose();
        long tA10e = System.currentTimeMillis();
        System.out.printf("Mode A-1.0 (as-is):              consistent=%b  (expected: true)   [%d ms]%n", modeA10, tA10e-tA10s);
        Assert.assertTrue("P4 Mode A-1.0: expected consistent", modeA10);

        // ── Modes A-1.1 through B-1.2 — copy baseOnt, inject, retranslate ────
        // Each injector adds standpoints, diamonds, or sharpenings that change
        // Π_K, so the full P1→P2→P3 chain must re-run after injection.

        // ── Mode A-1.1 — append □_s[C ⊑ ⊥]  +  □_t[C(a)],  s ≠ t ──────────
        // Separate worlds pi_s and pi_t → no clash.
        long tA11s = System.currentTimeMillis();
        OWLOntology ontA11 = copyOntology(baseOnt); injectBoxSatPattern(ontA11);
        OWLOntology trA11  = translate(ontA11);
        OWLReasoner rA11   = new Reasoner(new Configuration(), trA11);
        boolean modeA11    = rA11.isConsistent(); rA11.dispose();
        long tA11e = System.currentTimeMillis();
        System.out.printf("Mode A-1.1 (□_s[C⊑⊥]+□_t[Ca]):  consistent=%b  (expected: true)   [%d ms]%n", modeA11, tA11e-tA11s);
        Assert.assertTrue("P4 Mode A-1.1: expected consistent", modeA11);

        // ── Mode A-1.2 — append ◇_s[C ⊑ ⊥]  +  ◇_s[C(a)] ──────────────────
        // Two diamond formulas under s → two distinct fresh standpoints v≠w → no clash.
        long tA12s = System.currentTimeMillis();
        OWLOntology ontA12 = copyOntology(baseOnt); injectDiamondSatPattern(ontA12);
        OWLOntology trA12  = translate(ontA12);
        OWLReasoner rA12   = new Reasoner(new Configuration(), trA12);
        boolean modeA12    = rA12.isConsistent(); rA12.dispose();
        long tA12e = System.currentTimeMillis();
        System.out.printf("Mode A-1.2 (◇_s[C⊑⊥]+◇_s[Ca]):  consistent=%b  (expected: true)   [%d ms]%n", modeA12, tA12e-tA12s);
        Assert.assertTrue("P4 Mode A-1.2: expected consistent", modeA12);

        // ── Mode B-1.0 — append ContraC ⊑ ⊥  +  ContraC(ind) unlabelled ──────
        // Unlabelled → wrapped as □_*[...] → applies at every pi ∈ Π_K → clash.
        long tB10s = System.currentTimeMillis();
        OWLOntology ontB10 = copyOntology(baseOnt); injectContradiction(ontB10);
        OWLOntology trB10  = translate(ontB10);
        OWLReasoner rB10   = new Reasoner(new Configuration(), trB10);
        boolean modeB10    = rB10.isConsistent(); rB10.dispose();
        long tB10e = System.currentTimeMillis();
        System.out.printf("Mode B-1.0 (unlabelled contra):  consistent=%b  (expected: false)  [%d ms]%n", modeB10, tB10e-tB10s);
        Assert.assertFalse("P4 Mode B-1.0: expected inconsistent", modeB10);

        // ── Mode B-1.1 — append □_s[C ⊑ ⊥]  +  □_t[C(a)]  +  t ⪯ s ─────────
        // pi_t ∈ sigma(s) → C⊑⊥ at pi_t, but C(a) requires a ∈ C^{pi_t} → clash.
        long tB11s = System.currentTimeMillis();
        OWLOntology ontB11 = copyOntology(baseOnt); injectBoxUnsatPattern(ontB11);
        OWLOntology trB11  = translate(ontB11);
        OWLReasoner rB11   = new Reasoner(new Configuration(), trB11);
        boolean modeB11    = rB11.isConsistent(); rB11.dispose();
        long tB11e = System.currentTimeMillis();
        System.out.printf("Mode B-1.1 (□_s+□_t+t⪯s):       consistent=%b  (expected: false)  [%d ms]%n", modeB11, tB11e-tB11s);
        Assert.assertFalse("P4 Mode B-1.1: expected inconsistent", modeB11);

        // ── Mode B-1.2 — append ◇_s[C ⊑ ⊥]  +  □_s[C(a)] ───────────────────
        // Fresh v ⪯ s created; □_s[C(a)] applies at pi_v, but C⊑⊥ at pi_v → clash.
        long tB12s = System.currentTimeMillis();
        OWLOntology ontB12 = copyOntology(baseOnt); injectDiamondUnsatPattern(ontB12);
        OWLOntology trB12  = translate(ontB12);
        OWLReasoner rB12   = new Reasoner(new Configuration(), trB12);
        boolean modeB12    = rB12.isConsistent(); rB12.dispose();
        long tB12e = System.currentTimeMillis();
        System.out.printf("Mode B-1.2 (◇_s[C⊑⊥]+□_s[Ca]):  consistent=%b  (expected: false)  [%d ms]%n", modeB12, tB12e-tB12s);
        Assert.assertFalse("P4 Mode B-1.2: expected inconsistent", modeB12);
    }

    // ── Shared translation helper ─────────────────────────────────────────────

    private OWLOntology translate(OWLOntology ont) throws Exception {
        StandpointKnowledgeBase kb  = new NormalisationPipeline(ont).run();
        PrecisificationContext  ctx = new PrecisificationPipeline(kb).run();
        return new TranslationPipeline(ctx, null).run();
    }

    // =========================================================================
    // P4 injectors
    // =========================================================================

    /**
     * Mode A-1.1: □_sA[SA_C ⊑ ⊥]  +  □_sB[SA_C(sa_ind)]
     * sA and sB are independent — no sharpening, separate worlds → consistent.
     */
    private void injectBoxSatPattern(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory     df      = manager.getOWLDataFactory();
        String base = "http://standpoint.org/test#";
        OWLClass           C   = df.getOWLClass(IRI.create(base + "SA_C"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(base + "sa_ind"));
        OWLAnnotationProperty axiomProp   = df.getOWLAnnotationProperty(IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp = df.getOWLAnnotationProperty(IRI.create(base + "standpointFormula"));
        manager.addAxiom(ontology,
                df.getOWLSubClassOfAxiom(C, df.getOWLNothing())
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SA1\"></axiom>")))));
        manager.addAxiom(ontology,
                df.getOWLClassAssertionAxiom(C, ind)
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SA2\"></axiom>")))));
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"box\" standpoint=\"sA\"><literal ref=\"SA1\"/></formula>");
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"box\" standpoint=\"sB\"><literal ref=\"SA2\"/></formula>");
    }

    /**
     * Mode A-1.2: ◇_sD[SD_C ⊑ ⊥]  +  ◇_sD[SD_C(sd_ind)]
     * Two diamond formulas → two distinct fresh standpoints v≠w ⪯ sD → consistent.
     */
    private void injectDiamondSatPattern(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory     df      = manager.getOWLDataFactory();
        String base = "http://standpoint.org/test#";
        OWLClass           C   = df.getOWLClass(IRI.create(base + "SD_C"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(base + "sd_ind"));
        OWLAnnotationProperty axiomProp   = df.getOWLAnnotationProperty(IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp = df.getOWLAnnotationProperty(IRI.create(base + "standpointFormula"));
        manager.addAxiom(ontology,
                df.getOWLSubClassOfAxiom(C, df.getOWLNothing())
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SD1\"></axiom>")))));
        manager.addAxiom(ontology,
                df.getOWLClassAssertionAxiom(C, ind)
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SD2\"></axiom>")))));
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"diamond\" standpoint=\"sD\"><literal ref=\"SD1\"/></formula>");
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"diamond\" standpoint=\"sD\"><literal ref=\"SD2\"/></formula>");
    }

    /**
     * Mode B-1.0: ContraC ⊑ ⊥  +  ContraC(contra_ind)  (unlabelled, no standpointAxiom).
     * Unlabelled axioms are wrapped as □_*[...] → translate for every pi ∈ Π_K → clash.
     */
    private void injectContradiction(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory     df      = manager.getOWLDataFactory();
        String base = "http://standpoint.org/test#";
        OWLClass           C   = df.getOWLClass(IRI.create(base + "ContraC"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(base + "contra_ind"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(C, df.getOWLNothing()));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(C, ind));
    }

    /**
     * Mode B-1.1: □_sE[SE_C ⊑ ⊥]  +  □_sF[SE_C(se_ind)]  +  sF ⪯ sE
     * pi_sF ∈ sigma(sE) → SE_C⊑⊥ at pi_sF but SE_C(se_ind) requires
     * se_ind ∈ SE_C^{pi_sF} → contradiction.
     */
    private void injectBoxUnsatPattern(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory     df      = manager.getOWLDataFactory();
        String base = "http://standpoint.org/test#";
        OWLClass           C   = df.getOWLClass(IRI.create(base + "SE_C"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(base + "se_ind"));
        OWLAnnotationProperty axiomProp      = df.getOWLAnnotationProperty(IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp    = df.getOWLAnnotationProperty(IRI.create(base + "standpointFormula"));
        OWLAnnotationProperty sharpeningProp = df.getOWLAnnotationProperty(IRI.create(base + "standpointSharpening"));
        manager.addAxiom(ontology,
                df.getOWLSubClassOfAxiom(C, df.getOWLNothing())
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SE1\"></axiom>")))));
        manager.addAxiom(ontology,
                df.getOWLClassAssertionAxiom(C, ind)
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SE2\"></axiom>")))));
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"box\" standpoint=\"sE\"><literal ref=\"SE1\"/></formula>");
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"box\" standpoint=\"sF\"><literal ref=\"SE2\"/></formula>");
        manager.applyChange(new AddOntologyAnnotation(ontology, df.getOWLAnnotation(
                sharpeningProp, df.getOWLLiteral(
                        "<sharpening><lhs><standpoint>sF</standpoint></lhs>" +
                                "<rhs><standpoint>sE</standpoint></rhs></sharpening>"))));
    }

    /**
     * Mode B-1.2: ◇_sG[SG_C ⊑ ⊥]  +  □_sG[SG_C(sg_ind)]
     * Rule (1) creates fresh v ⪯ sG; □_sG[SG_C(sg_ind)] applies at pi_v,
     * but SG_C⊑⊥ holds at pi_v → contradiction.
     */
    private void injectDiamondUnsatPattern(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory     df      = manager.getOWLDataFactory();
        String base = "http://standpoint.org/test#";
        OWLClass           C   = df.getOWLClass(IRI.create(base + "SG_C"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(base + "sg_ind"));
        OWLAnnotationProperty axiomProp   = df.getOWLAnnotationProperty(IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp = df.getOWLAnnotationProperty(IRI.create(base + "standpointFormula"));
        manager.addAxiom(ontology,
                df.getOWLSubClassOfAxiom(C, df.getOWLNothing())
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SG1\"></axiom>")))));
        manager.addAxiom(ontology,
                df.getOWLClassAssertionAxiom(C, ind)
                        .getAnnotatedAxiom(singleton(df.getOWLAnnotation(axiomProp,
                                df.getOWLLiteral("<axiom id=\"SG2\"></axiom>")))));
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"diamond\" standpoint=\"sG\"><literal ref=\"SG1\"/></formula>");
        addFormula(manager, ontology, df, formulaProp,
                "<formula op=\"box\" standpoint=\"sG\"><literal ref=\"SG2\"/></formula>");
    }

    // ── Shared injector helper ────────────────────────────────────────────────

    private void addFormula(OWLOntologyManager manager, OWLOntology ontology,
                            OWLDataFactory df, OWLAnnotationProperty formulaProp,
                            String xml) {
        manager.applyChange(new AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(formulaProp, df.getOWLLiteral(xml))));
    }

    private Set<OWLAnnotation> singleton(OWLAnnotation ann) {
        return Collections.singleton(ann);
    }

    // =========================================================================
    // Expected count helpers
    // =========================================================================

    private int computeExpectedTranslationAxiomCount(
            StandpointKnowledgeBase kb, PrecisificationContext ctx) {
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

    // =========================================================================
    // GeneratedAxiom and generators
    // =========================================================================

    private static class GeneratedAxiom {
        public final String id;
        public final StandpointAxiomType kind;
        public final boolean negated;
        public final String nameA, nameB, nameC;
        public final String modalOp, modalStandpoint;
        public final boolean modalInnerNeg;

        public GeneratedAxiom(String id, StandpointAxiomType kind, boolean negated,
                              String nameA, String nameB, String nameC,
                              String modalOp, String modalStandpoint,
                              boolean modalInnerNeg) {
            this.id = id; this.kind = kind; this.negated = negated;
            this.nameA = nameA; this.nameB = nameB; this.nameC = nameC;
            this.modalOp = modalOp; this.modalStandpoint = modalStandpoint;
            this.modalInnerNeg = modalInnerNeg;
        }

        public boolean hasModal() { return modalOp != null; }
    }

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
                body.append(ax.nameA).append(" Type: ").append(ax.nameB); break;
            case ROLE_INCLUSION:
                body.append(ax.nameA).append(" SubPropertyOf: ").append(ax.nameB); break;
            case ROLE_ASSERTION:
                body.append("Individual: ").append(ax.nameA)
                        .append(" Facts: ").append(ax.nameB)
                        .append(" ").append(ax.nameC); break;
            case ROLE_TRANSITIVITY:
                body.append("Transitive ").append(ax.nameA); break;
        }
        return "<axiom id=\"" + ax.id + "\">" + body + "</axiom>";
    }

    // ── Formula / sharpening generators ──────────────────────────────────────

    private static class GeneratedFormula {
        public final String operator, standpoint;
        public final List<GeneratedAxiom> literals;
        public GeneratedFormula(String op, String sp, List<GeneratedAxiom> lits) {
            operator = op; standpoint = sp; literals = lits;
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

    private List<String> extractStandpoints(List<GeneratedFormula> formulas) {
        List<String> out = new ArrayList<>();
        for (GeneratedFormula f : formulas) out.add(f.standpoint);
        return out;
    }

    private enum SharpeningKind { NORMAL, ZERO, NEGATED }

    private static class GeneratedSharpening {
        public final List<String> lhs;
        public final String rhs;
        public final SharpeningKind kind;
        public GeneratedSharpening(List<String> lhs, String rhs, SharpeningKind kind) {
            this.lhs = lhs; this.rhs = rhs; this.kind = kind;
        }
    }

    /**
     * Generates sharpenings using "FSM_" prefix to avoid collision with the
     * pipeline's own "FS_" prefix used by Rule (1) for diamond formulas.
     */
    private List<GeneratedSharpening> generateSharpening(
            List<String> standpoints, int numNormal, int numZero, int numNegated) {
        List<GeneratedSharpening> result = new ArrayList<>();
        List<String> available = new ArrayList<>(standpoints);
        Collections.shuffle(available, new Random());
        int freshCounter = 0;
        for (int i = 0; i < numNormal; i++) {
            while (available.size() < 2) available.add("FSM_" + (++freshCounter));
            result.add(new GeneratedSharpening(
                    Collections.singletonList(available.remove(0)),
                    available.remove(0), SharpeningKind.NORMAL));
        }
        for (int i = 0; i < numZero; i++) {
            while (available.size() < 2) available.add("FSM_" + (++freshCounter));
            result.add(new GeneratedSharpening(
                    Arrays.asList(available.remove(0), available.remove(0)),
                    "0", SharpeningKind.ZERO));
        }
        for (int i = 0; i < numNegated; i++) {
            while (available.size() < 2) available.add("FSM_" + (++freshCounter));
            result.add(new GeneratedSharpening(
                    Collections.singletonList(available.remove(0)),
                    available.remove(0), SharpeningKind.NEGATED));
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
            for (GeneratedAxiom a : f.literals) referenced.add(a.id);
        for (GeneratedAxiom a : allAxioms)
            if (!referenced.contains(a.id)) counts.axiomCount++;

        for (GeneratedFormula formula : formulas) {
            if ("diamond".equals(formula.operator)) counts.sharpeningCount++;
            for (GeneratedAxiom ax : formula.literals) {
                if (ax.negated) {
                    switch (ax.kind) {
                        case CONCEPT_INCLUSION:
                            counts.axiomCount += 3; counts.freshConceptCount++;
                            counts.freshRoleCount++; break;
                        case CONCEPT_ASSERTION:
                            counts.axiomCount++; break;
                        case ROLE_INCLUSION:
                            counts.axiomCount += 3; counts.freshConceptCount += 2;
                            counts.freshRoleCount++; break;
                        case ROLE_ASSERTION:
                            counts.axiomCount += 3; counts.freshConceptCount += 2; break;
                        case ROLE_TRANSITIVITY:
                            counts.axiomCount += 3; counts.freshConceptCount += 2;
                            counts.freshRoleCount++; break;
                    }
                } else { counts.axiomCount++; }
            }
        }
        for (GeneratedSharpening s : sharpenings) {
            int n = s.lhs.size();
            switch (s.kind) {
                case NORMAL:  counts.sharpeningCount++;                          break;
                case ZERO:    counts.axiomCount += n+1; counts.freshConceptCount += n; break;
                case NEGATED: counts.sharpeningCount += n; counts.axiomCount += 3;
                    counts.freshConceptCount += 2;                     break;
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

        OWLAnnotationProperty axiomProp      = df.getOWLAnnotationProperty(IRI.create(base + "standpointAxiom"));
        OWLAnnotationProperty formulaProp    = df.getOWLAnnotationProperty(IRI.create(base + "standpointFormula"));
        OWLAnnotationProperty sharpeningProp = df.getOWLAnnotationProperty(IRI.create(base + "standpointSharpening"));
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
                    df.getOWLAnnotation(formulaProp, df.getOWLLiteral(buildFormulaXml(f)))));
        for (GeneratedSharpening s : sharpenings)
            manager.applyChange(new AddOntologyAnnotation(ontology,
                    df.getOWLAnnotation(sharpeningProp, df.getOWLLiteral(buildSharpeningXml(s)))));
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

    /**
     * Creates a new independent copy of the given ontology —
     * same axioms and annotations, fresh manager — so injectors
     * can modify it without affecting the base.
     */
    private OWLOntology copyOntology(OWLOntology source) throws Exception {
        OWLOntologyManager newMgr = OWLManager.createOWLOntologyManager();
        OWLOntology copy = newMgr.createOntology(
                IRI.create("http://standpoint.org/test"));
        newMgr.addAxioms(copy, source.getAxioms());
        for (OWLAnnotation ann : source.getAnnotations())
            newMgr.applyChange(new AddOntologyAnnotation(copy, ann));
        return copy;
    }
}