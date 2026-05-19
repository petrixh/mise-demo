package com.example.mise.ui.shared;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;

/**
 * Reusable KPI card: a labelled metric tile used in the KPI strips of the
 * Plan and Reports views.  Applies the standard mise-kpi-* CSS classes so
 * appearance is driven entirely by the stylesheet.
 */
public class KpiCard extends Div {

    public KpiCard(String label, String value) {
        this(label, value, false, null);
    }

    public KpiCard(String label, String value, boolean warn) {
        this(label, value, warn, null);
    }

    public KpiCard(String label, String value, boolean warn, String testId) {
        addClassName("mise-kpi-card");
        if (testId != null) {
            getElement().setAttribute("data-testid", testId);
        }

        var lbl = new Paragraph(label);
        lbl.addClassName("mise-kpi-label");

        var val = new Span(value);
        val.addClassName("mise-kpi-value");
        if (warn) {
            val.addClassName("mise-kpi-value-warn");
        }

        add(lbl, val);
    }
}
