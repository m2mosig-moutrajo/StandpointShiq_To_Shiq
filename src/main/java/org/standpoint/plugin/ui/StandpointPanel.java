package org.standpoint.plugin.ui;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.standpoint.plugin.model.ModalPlaceholder;
import org.standpoint.plugin.pipeline.PipelineResult;
import org.standpoint.plugin.pipeline.StandpointPipeline;
import org.standpoint.plugin.util.PipelineLogger;

import javax.swing.*;
import java.awt.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;

public class StandpointPanel extends JPanel {

    private final JButton buttonTranslate;
    private final JButton buttonClear;
    private final JTextArea textAreaLog;
    private final JCheckBox checkVerbose;
    private final OWLModelManager modelManager;

    public StandpointPanel(OWLModelManager modelManager) {
        this.modelManager = modelManager;
        setLayout(new BorderLayout());

        buttonTranslate = new JButton("Translate");
        buttonClear     = new JButton("Clear");
        checkVerbose    = new JCheckBox("Translation Log");
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

    private void onTranslateClicked() {
        try {
            OWLOntology ontology = modelManager != null
                    ? modelManager.getActiveOntology()
                    : null;

            if (ontology == null) {
                textAreaLog.append("No ontology loaded.\n");
                return;
            }

            // Redirect System.out to textAreaLog if verbose
            PrintStream originalOut = System.out;
            if (checkVerbose.isSelected()) {
                PrintStream uiStream = new PrintStream(new OutputStream() {
                    private final StringBuilder line = new StringBuilder();

                    @Override
                    public void write(int b) {
                        char c = (char) b;
                        if (c == '\n') {
                            final String text = line.toString();
                            SwingUtilities.invokeLater(() -> textAreaLog.append(text + "\n"));
                            line.setLength(0);
                        } else {
                            line.append(c);
                        }
                    }
                });
                System.setOut(uiStream);
            }

            PipelineLogger.Level logLevel = checkVerbose.isSelected()
                    ? PipelineLogger.Level.ON
                    : PipelineLogger.Level.OFF;

            StandpointPipeline pipeline = new StandpointPipeline(ontology, logLevel);
            PipelineResult result = pipeline.run();

            // Restore System.out
            System.setOut(originalOut);

            if (result == null || result.isEmpty()) {
                textAreaLog.append("No results.\n");
                return;
            }

            textAreaLog.append("=== NORMALISED MAP ===\n");
            for (Map.Entry<String, ModalPlaceholder> e :
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