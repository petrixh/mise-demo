package com.example.mise.ui;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AnchorTarget;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.theme.lumo.LumoUtility;

public class MainLayout extends AppLayout {

    public MainLayout() {
        addToNavbar(buildHeader());
        addToDrawer(buildDrawer());
    }

    private HorizontalLayout buildHeader() {
        var title = new H1("Mise");
        title.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.Margin.NONE);
        var bar = new HorizontalLayout(new DrawerToggle(), title);
        bar.setWidthFull();
        bar.expand(title);
        bar.setPadding(true);
        bar.setSpacing(true);
        return bar;
    }

    private SideNav buildDrawer() {
        var nav = new SideNav();
        nav.addItem(new SideNavItem("Home", HomeView.class, VaadinIcon.HOME.create()));

        // H2 console is a separate servlet (not a Vaadin @Route), so it must be
        // an anchor with absolute URL rather than a SideNavItem/RouterLink.
        var consoleItem = new SideNavItem("H2 Console");
        consoleItem.setPrefixComponent(VaadinIcon.DATABASE.create());
        var consoleLink = new Anchor("/h2-console", "H2 Console");
        consoleLink.setTarget(AnchorTarget.BLANK);
        consoleLink.getElement().getStyle().set("text-decoration", "none");
        consoleLink.removeAll();
        consoleLink.add(VaadinIcon.DATABASE.create());
        var consoleLabel = new com.vaadin.flow.component.html.Span("H2 Console");
        consoleLabel.getStyle().set("margin-left", "var(--lumo-space-s)");
        consoleLink.add(consoleLabel);
        consoleLink.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("color", "var(--lumo-body-text-color)");
        nav.getElement().appendChild(consoleLink.getElement());
        return nav;
    }
}
