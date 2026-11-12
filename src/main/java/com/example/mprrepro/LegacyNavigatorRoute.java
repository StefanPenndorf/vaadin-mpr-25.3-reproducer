package com.example.mprrepro;

import com.vaadin.flow.router.Route;
import com.vaadin.mpr.MprNavigatorRoute;
import com.vaadin.navigator.Navigator;

/**
 * Entry route. Hosts the legacy (Vaadin 8) Navigator, as in an application that is being migrated view by view.
 * This is the only legacy wrapper on the page.
 */
@Route("")
public class LegacyNavigatorRoute extends MprNavigatorRoute {

    @Override
    public void configureNavigator(Navigator navigator) {
        navigator.addView("", LegacyStartView.class);
    }
}
