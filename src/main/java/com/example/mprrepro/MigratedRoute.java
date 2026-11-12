package com.example.mprrepro;

import com.vaadin.flow.router.Route;
import com.vaadin.mpr.MprRouteAdapter;

/**
 * A former legacy view that has been migrated to its own Flow route via {@link MprRouteAdapter}.
 */
@Route("migrated")
public class MigratedRoute extends MprRouteAdapter<MigratedLegacyView> {
}
