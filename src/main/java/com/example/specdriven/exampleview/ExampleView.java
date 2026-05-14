package com.example.specdriven.exampleview;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@Route("") 
@PageTitle("Hello, Vaadin!") 
public class ExampleView extends VerticalLayout { 
    public ExampleView() {
        add(new H1("Hello, Vaadin!")); 
    }
}
