package com.example.mise.ui.shared;

import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.*;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.charts.model.style.Style;

/**
 * Vaadin {@link Chart} subclass that applies the Mise dark-panel theme.
 *
 * <p>Construct it with the desired {@link ChartType}, then configure axes,
 * series, and legend layout as normal — the theme (transparent canvas,
 * hairline grid lines, panel-text label colours) is always applied.
 *
 * <p><strong>Axis ordering:</strong> theme styling requires axes to be
 * registered on the {@link Configuration} first.  Call {@link #applyTheme()}
 * explicitly after adding axes — the constructor calls it once as a
 * convenience for pie/donut charts that have no axes.
 *
 * <p><strong>Why SolidColor uses int constructors:</strong> the
 * {@code SolidColor(String)} constructor stores the value verbatim, and the
 * Vaadin Charts JSON serialiser strips parentheses and commas, turning
 * {@code "rgba(0,0,0,0)"} into {@code "rgba0,0,0,0"} — an invalid SVG
 * colour that Highcharts renders as a black canvas.  Always use
 * {@code SolidColor(int r, int g, int b, double a)} instead.
 */
public class MiseChart extends Chart {

    /** Transparent — lets the panel background show through the chart canvas. */
    public static final SolidColor BG       = solidColor(0, 0, 0, 0.0);
    /** Faint white hairline for axis and grid lines. */
    public static final SolidColor HAIRLINE = solidColor(255, 255, 255, 0.08);
    /** Primary panel text colour for legend items. */
    public static final SolidColor TEXT     = solidColor(228, 228, 231, 0.78);
    /** Secondary panel text colour for axis tick labels and titles. */
    public static final SolidColor LABEL    = solidColor(228, 228, 231, 0.62);

    public MiseChart(ChartType type) {
        super(type);
        applyTheme();
    }

    /**
     * Applies (or re-applies) the Mise theme to this chart's current
     * {@link Configuration}.  Call after adding axes so the axis style
     * helpers can find the registered axis instances.
     */
    public void applyTheme() {
        Configuration conf = getConfiguration();

        conf.getChart().setBackgroundColor(BG);
        conf.getChart().setPlotBackgroundColor(BG);

        styleXAxis(conf.getxAxis());
        styleYAxis(conf.getyAxis());

        Legend legend = conf.getLegend();
        if (legend != null) {
            Style itemStyle = new Style();
            itemStyle.setColor(TEXT);
            legend.setItemStyle(itemStyle);

            Style hoverStyle = new Style();
            hoverStyle.setColor(SolidColor.WHITE);
            legend.setItemHoverStyle(hoverStyle);
        }
    }

    private void styleXAxis(XAxis axis) {
        if (axis == null) return;
        axis.setLineColor(HAIRLINE);
        axis.setTickColor(HAIRLINE);
        axis.setGridLineColor(HAIRLINE);
        applyAxisLabelStyle(axis);
    }

    private void styleYAxis(YAxis axis) {
        if (axis == null) return;
        axis.setLineColor(HAIRLINE);
        axis.setTickColor(HAIRLINE);
        axis.setGridLineColor(HAIRLINE);
        applyAxisLabelStyle(axis);
    }

    private void applyAxisLabelStyle(Axis axis) {
        Labels labels = axis.getLabels() != null ? axis.getLabels() : new Labels();
        Style labelStyle = labels.getStyle() != null ? labels.getStyle() : new Style();
        labelStyle.setColor(LABEL);
        if (labelStyle.getFontSize() == null) labelStyle.setFontSize("10px");
        labels.setStyle(labelStyle);
        axis.setLabels(labels);

        AxisTitle title = axis.getTitle();
        if (title != null) {
            Style titleStyle = title.getStyle() != null ? title.getStyle() : new Style();
            titleStyle.setColor(TEXT);
            title.setStyle(titleStyle);
            axis.setTitle(title);
        }
    }

    private static SolidColor solidColor(int r, int g, int b, double a) {
        return new SolidColor(r, g, b, a);
    }
}
