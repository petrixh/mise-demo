package com.example.mise.ui;

import com.example.mise.ai.HouseholdOrchestrator;
import com.example.mise.domain.conversation.ConversationService;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.ai.provider.LLMProvider;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AnchorTarget;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.theme.lumo.LumoUtility;

public class MainLayout extends AppLayout {

    private final HouseholdOrchestrator household;

    public MainLayout(LLMProvider llmProvider, ConversationService conversationService) {
        var messageList = new MessageList();
        messageList.setMarkdown(true);
        messageList.setSizeFull();

        var messageInput = new MessageInput();
        messageInput.setWidthFull();

        this.household = new HouseholdOrchestrator(
                llmProvider, conversationService, messageList, messageInput);

        addToNavbar(buildHeader());
        addToDrawer(buildDrawer());
        addToDrawer(buildChatPanel(messageList, messageInput));
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

        // H2 console is a separate servlet, not a Vaadin @Route — render as a
        // plain anchor styled to fit the side-nav rather than a SideNavItem.
        var consoleLink = new Anchor("/h2-console", "");
        consoleLink.setTarget(AnchorTarget.BLANK);
        consoleLink.add(VaadinIcon.DATABASE.create());
        var label = new com.vaadin.flow.component.html.Span("H2 Console");
        label.getStyle().set("margin-left", "var(--lumo-space-s)");
        consoleLink.add(label);
        consoleLink.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("color", "var(--lumo-body-text-color)")
                .set("text-decoration", "none");
        nav.getElement().appendChild(consoleLink.getElement());
        return nav;
    }

    private VerticalLayout buildChatPanel(MessageList messageList, MessageInput messageInput) {
        var heading = new com.vaadin.flow.component.html.H2("Chat");
        heading.addClassNames(LumoUtility.FontSize.MEDIUM, LumoUtility.Margin.NONE);

        var panel = new VerticalLayout(heading, messageList, messageInput);
        panel.setSizeFull();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.expand(messageList);
        return panel;
    }

    public HouseholdOrchestrator household() {
        return household;
    }
}
