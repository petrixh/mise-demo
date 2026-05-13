package com.example.mise.ui;

import com.example.mise.domain.household.HouseholdService;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;

/**
 * Root route ({@code ""}) — immediately redirects based on app state:
 * <ul>
 *   <li>No household → {@code /welcome} (onboarding)</li>
 *   <li>Household exists → {@code /plan}</li>
 * </ul>
 */
@Route("")
public class RootRedirectView extends VerticalLayout implements BeforeEnterObserver {

    private final HouseholdService householdService;

    public RootRedirectView(HouseholdService householdService) {
        this.householdService = householdService;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (householdService.exists()) {
            event.forwardTo("plan");
        } else {
            event.forwardTo("welcome");
        }
    }
}
