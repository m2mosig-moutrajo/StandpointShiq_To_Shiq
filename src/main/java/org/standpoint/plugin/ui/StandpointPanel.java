package org.standpoint.plugin.ui;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.standpoint.plugin.pipeline.PipelineResult;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.util.PipelineLogger;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

public class StandpointPanel extends JPanel {

    private final JButton buttonTranslate;
    private final JButton buttonClear;
    private final JTextArea textAreaLog;
    private final OWLModelManager modelManager;

    public StandpointPanel(OWLModelManager modelManager) {
        this.modelManager = modelManager;
        setLayout(new BorderLayout());

        buttonTranslate = new JButton("Translate");
        buttonClear = new JButton("Clear");
        textAreaLog = new JTextArea();
        textAreaLog.setEditable(false);
        textAreaLog.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(buttonTranslate);
        topPanel.add(buttonClear);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(textAreaLog), BorderLayout.CENTER);

        buttonTranslate.addActionListener(e -> onTranslateClicked());
        buttonClear.addActionListener(e -> onClearClicked());
    }

    private void onTranslateClicked() {
        try {
            OWLOntology ontology = modelManager != null
                    ? modelManager.getActiveOntology()
                    : null;

            if (ontology == null) {
                textAreaLog.append("No ontology loaded.\n");
                return;
            }

            StandpointPipeline pipeline = new StandpointPipeline(ontology, PipelineLogger.Level.OFF);
            PipelineResult result = pipeline.run();

            if (result == null || result.isEmpty()) {
                textAreaLog.append("No results.\n");
                return;
            }

            textAreaLog.append("=== NORMALISED MAP ===\n");
            for (Map.Entry<String, org.standpoint.plugin.parser.PlaceholderSubstituter.PlaceholderEntry> e :
                    result.normalisedPlaceholderMap.entrySet()) {
                textAreaLog.append(e.getKey() + " → " + e.getValue() + "\n");
            }

            textAreaLog.append("\n=== SHARPENINGS ===\n");
            if (result.sharpenings.isEmpty()) {
                textAreaLog.append("(none)\n");
            } else {
                for (var s : result.sharpenings) {
                    textAreaLog.append(s + "\n");
                }
            }

        } catch (Exception ex) {
            textAreaLog.append("Error: " + ex.getMessage() + "\n");
            ex.printStackTrace();
        }
    }

    private void onClearClicked() {
        textAreaLog.setText("");
    }

    // Standalone preview — no Protégé
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Standpoint Plugin Preview");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);
            frame.add(new StandpointPanel(null));
            frame.setVisible(true);
        });
    }
}