package org.standpoint.plugin.ui;

import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;

import java.awt.*;

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