package com.example.mise.ui.shared;

import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.*;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.charts.model.style.Style;

/**
 * Vaadin {@link Chart} subclass that applies the Mise dark-panel theme.
 *
 * <p>Construct it with the desired {@link ChartType}, configure axes and
 * series as normal, then call {@link #applyTheme()} once at the end.
 *
 * <p><strong>Why applyTheme() is NOT called in the constructor:</strong>
 * {@link Configuration#getxAxis()} and {@link Configuration#getyAxis()}
 * lazily materialise a default axis and register it when called on an empty
 * Configuration.  Calling them inside the constructor would create phantom
 * "index 0" axes; any axes the caller subsequently adds via
 * {@code conf.addxAxis()} become index 1, which Highcharts ignores for
 * default series assignment — resulting in bars rendering against numeric
 * indices instead of the caller's categories.  Only the transparent canvas
 * background is set in the constructor (safe because it touches no axes).
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
        // Transparent canvas immediately — safe because it touches no axes.
        Configuration conf = getConfiguration();
        conf.getChart().setBackgroundColor(BG);
        conf.getChart().setPlotBackgroundColor(BG);
    }

    /**
     * Applies the Mise theme: transparent canvas, hairline axes/grid,
     * and panel-text label colours.  Call once after all axes are
     * registered on the Configuration so the styling reaches the right
     * axis instances.
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
