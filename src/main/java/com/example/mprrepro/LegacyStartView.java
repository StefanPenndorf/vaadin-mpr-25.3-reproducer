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
 * <p>
 * Workaround: the navigation only happens once the browser reports that no legacy client is busy any more, i.e. the
 * legacy client has processed its first message and attached its widgets.
 */
public class LegacyStartView extends VerticalLayout implements View {

    // Resolves once every legacy client is idle; gives up after 10 s so that navigation never blocks forever
    private static final String WAIT_UNTIL_LEGACY_CLIENTS_IDLE = """
            return new Promise(resolve => {
                const deadline = Date.now() + 10000;
                (function poll() {
                    const legacyClients = Object.values((window.vaadin && window.vaadin.clients) || {});
                    const idle = legacyClients.length > 0 && legacyClients.every(client => !client.isActive());
                    if (idle || Date.now() > deadline) {
                        resolve(idle);
                    } else {
                        setTimeout(poll, 50);
                    }
                })();
            });
            """;

    public LegacyStartView() {
        addComponent(new Label("Legacy start view (should be replaced by the migrated route immediately)"));
    }

    @Override
    public void enter(ViewChangeEvent event) {
        UI flowUi = UI.getCurrent();
        flowUi.beforeClientResponse(flowUi, context -> flowUi.getPage()
                .executeJs(WAIT_UNTIL_LEGACY_CLIENTS_IDLE)
                .then(Boolean.class, idle -> flowUi.navigate(MigratedRoute.class)));
    }
}
