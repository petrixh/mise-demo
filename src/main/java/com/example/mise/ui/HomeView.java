package com.example.mise.ui;

import com.example.mise.domain.conversation.ConversationMessage;
import com.example.mise.domain.conversation.ConversationMessageRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.time.Instant;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Mise")
public class HomeView extends VerticalLayout {

    public HomeView(ConversationMessageRepository conversations) {
        setSpacing(true);
        setPadding(true);

        add(new H2("Mise — prep build"));
        add(new Paragraph(
                "Project skeleton with persistent H2. The H2 console is in the side drawer."));

        var count = new Span("Stored messages: " + conversations.count());
        add(count);

        var seedButton = new Button("Insert a test conversation message", e -> {
            var msg = new ConversationMessage();
            msg.setRole(ConversationMessage.Role.USER);
            msg.setContent("Test message at " + Instant.now());
            msg.setViewContext(ConversationMessage.ViewContext.PLAN);
            conversations.save(msg);
            count.setText("Stored messages: " + conversations.count());
            Notification.show("Inserted — refresh the H2 console to see it.");
        });
        add(seedButton);
    }
}
