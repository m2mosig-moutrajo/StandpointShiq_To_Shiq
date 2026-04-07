package org.standpoint.plugin.ui;

import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.*;
import org.standpoint.plugin.parser.PlaceholderSubstituter;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.Set;

public class StandpointTabPlugin extends AbstractOWLViewComponent {

    private StandpointPanel panel;

    @Override
    protected void initialiseOWLView() throws Exception {
        setLayout(new BorderLayout());
        panel = new StandpointPanel(getOWLModelManager());
        add(panel, BorderLayout.CENTER);
    }

    @Override
    protected void disposeOWLView() {
    }
}