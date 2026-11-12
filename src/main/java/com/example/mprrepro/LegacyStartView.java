package com.example.mprrepro;

import com.vaadin.flow.component.UI;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent;
import com.vaadin.ui.Label;
import com.vaadin.ui.VerticalLayout;

/**
 * Initial view of the legacy Navigator. Its content has already been migrated to the Flow route
 * {@link MigratedRoute}, so entering it forwards to that route.
 * <p>
 * The legacy Navigator performs this initial navigation while the legacy UI is being bootstrapped (the
 * {@code /framework} request). The Flow navigation is scheduled for the next Flow response instead of being run
 * inside the legacy request.
 */
public class LegacyStartView extends VerticalLayout implements View {

    public LegacyStartView() {
        addComponent(new Label("Legacy start view (should be replaced by the migrated route immediately)"));
    }

    @Override
    public void enter(ViewChangeEvent event) {
        UI flowUi = UI.getCurrent();
        flowUi.beforeClientResponse(flowUi, context -> flowUi.navigate(MigratedRoute.class));
    }
}
