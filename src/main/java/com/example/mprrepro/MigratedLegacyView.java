package com.example.mprrepro;

import com.vaadin.navigator.View;
import com.vaadin.ui.Label;
import com.vaadin.ui.VerticalLayout;

/**
 * Legacy (Vaadin 8) content shown by {@link MigratedRoute}. If this text is visible, the legacy client works.
 */
public class MigratedLegacyView extends VerticalLayout implements View {

    public MigratedLegacyView() {
        Label label = new Label("Legacy content rendered - the legacy client is working.");
        label.setId("legacy-content");
        addComponent(label);
    }
}
