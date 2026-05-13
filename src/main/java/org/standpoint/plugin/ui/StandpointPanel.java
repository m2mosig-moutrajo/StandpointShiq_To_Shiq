package org.standpoint.plugin.ui;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyAlreadyExistsException;
import org.standpoint.plugin.pipeline.NormalisationPipeline;
import org.standpoint.plugin.pipeline.PrecisificationPipeline;
import org.standpoint.plugin.pipeline.TranslationPipeline;
import org.standpoint.plugin.pipeline.data.StandpointKnowledgeBase;
import org.standpoint.plugin.pipeline.precisification.PrecisificationContext;
import org.standpoint.plugin.util.PipelineLogger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;

public class StandpointPanel extends JPanel {

    private final JButton         buttonTranslate;
    private final JButton         buttonClear;
    private final JCheckBox       checkVerbose;
    private final JTextArea       textAreaLog;
    private final OWLModelManager modelManager;

    public StandpointPanel(OWLModelManager modelManager) {
        this.modelManager = modelManager;
        setLayout(new BorderLayout());

        buttonTranslate = new JButton("Translate");
        buttonClear     = new JButton("Clear");
        checkVerbose    = new JCheckBox("Log");
        checkVerbose.setSelected(false);

        textAreaLog = new JTextArea();
        textAreaLog.setEditable(false);
        textAreaLog.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(buttonTranslate);
        topPanel.add(buttonClear);
        topPanel.add(checkVerbose);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(textAreaLog), BorderLayout.CENTER);

        buttonTranslate.addActionListener(e -> onTranslateClicked());
        buttonClear.addActionListener(e -> onClearClicked());
    }

    // ── Main translate action ──────────────────────────────────────────────
    private void onTranslateClicked() {

        OWLOntology ontology = modelManager != null
                ? modelManager.getActiveOntology()
                : null;

        if (ontology == null) {
            textAreaLog.append("No ontology loaded in Protégé.\n");
            return;
        }

        PipelineLogger.Level logLevel = checkVerbose.isSelected()
                ? PipelineLogger.Level.ON
                : PipelineLogger.Level.OFF;

        // Redirect System.out to textAreaLog when verbose
        PrintStream originalOut = System.out;
        if (checkVerbose.isSelected()) {
            System.setOut(buildUiStream());
        }

        try {
            // ── Pipeline 1 — Normalisation ─────────────────────────────
            textAreaLog.append("==== Pipeline 1 — Normalisation ====\n");
            StandpointKnowledgeBase kb =
                    new NormalisationPipeline(ontology).run();

            if (kb == null || kb.manchesterMap.isEmpty()) {
                textAreaLog.append("No standpoint formulas found.\n");
                return;
            }

            textAreaLog.append("Placeholder map size: "
                    + kb.manchesterMap.size() + "\n");

            // ── Pipeline 2 — Build worlds ──────────────────────────────
            textAreaLog.append("\n==== Pipeline 2 — Precisification ====\n");
            PrecisificationContext ctx =
                    new PrecisificationPipeline(kb).run();

            textAreaLog.append("Precisifications built: "
                    + ctx.precSet.size() + "\n");

            // ── Pipeline 3 — Translate ─────────────────────────────────
            // outputFile is null — pipeline returns OWLOntology without saving
            textAreaLog.append("\n==== Pipeline 3 — Translation ====\n");
            OWLOntology translated =
                    new TranslationPipeline(kb, ctx, null).run();

            textAreaLog.append("Axioms produced: "
                    + translated.getAxiomCount() + "\n");

            // ── Load result into Protégé ───────────────────────────────
            loadIntoProtege(translated);

        } catch (Exception ex) {
            textAreaLog.append("\nError: " + ex.getMessage() + "\n");
            ex.printStackTrace();
        } finally {
            // Always restore System.out even if pipeline throws
            System.setOut(originalOut);
        }
    }

    /**
     * Saves the translated ontology to a temp file, then loads it into
     * Protégé's ontology manager so it appears as a new ontology tab
     * alongside the original. Protégé's ontology selector lets the user
     * switch between the original and the translation.
     */
    private void loadIntoProtege(OWLOntology translated) {
        try {
            // Step 1 — Save to a temp file so Protégé can load it cleanly
            File tempFile = File.createTempFile("standpoint_translated_", ".rdf");
            tempFile.deleteOnExit();

            translated.getOWLOntologyManager().saveOntology(
                    translated,
                    new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(),
                    IRI.create(tempFile.toURI()));

            textAreaLog.append("  Temp file: " + tempFile.getAbsolutePath() + "\n");

            // Step 2 — Load into Protégé's own ontology manager
            // Registers it as a new ontology in the current session
            OWLOntology loaded = modelManager.getOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(tempFile);

            // Step 3 — Make it active so Protégé switches to it
            modelManager.setActiveOntology(loaded);

            textAreaLog.append("Translated ontology loaded into Protégé.\n");
            textAreaLog.append("Use the ontology selector at the top of Protégé\n");
            textAreaLog.append("to switch between the original and the translation.\n");

        } catch (OWLOntologyAlreadyExistsException ex) {
            // Happens if you translate the same ontology twice in one session
            textAreaLog.append("A translated ontology with this IRI is already open.\n");
            textAreaLog.append("Close it in Protégé first, then translate again.\n");

        } catch (Exception ex) {
            textAreaLog.append("Could not load into Protégé: "
                    + ex.getMessage() + "\n");
            ex.printStackTrace();
        }
    }

    // ── Builds a PrintStream that routes output to textAreaLog ─────────────
    private PrintStream buildUiStream() {
        return new PrintStream(new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int b) {
                char c = (char) b;
                if (c == '\n') {
                    final String text = line.toString();
                    SwingUtilities.invokeLater(
                            () -> textAreaLog.append(text + "\n"));
                    line.setLength(0);
                } else {
                    line.append(c);
                }
            }
        });
    }

    // ── Clear log ──────────────────────────────────────────────────────────
    private void onClearClicked() {
        textAreaLog.setText("");
    }

    // ── Standalone preview — runs without Protégé ─────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Standpoint Plugin Preview");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(900, 650);
            frame.add(new StandpointPanel(null));
            frame.setVisible(true);
        });
    }
}