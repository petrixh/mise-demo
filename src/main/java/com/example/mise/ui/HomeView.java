package com.example.mise.ui;

import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Mise")
public class HomeView extends VerticalLayout {

    public HomeView(ConversationMessageRepository conversations) {
        setSpacing(true);
        setPadding(true);

        add(new H2("Mise — prep build (stage 2)"));
        add(new Paragraph(
                "AI backbone wired. Open the drawer for the chat panel and the H2 console link."));
        add(new Paragraph(
                "Try: \"give me three quick dinner ideas\". Messages persist across restarts."));

        add(new Span("Stored messages: " + conversations.count()));
    }
}
