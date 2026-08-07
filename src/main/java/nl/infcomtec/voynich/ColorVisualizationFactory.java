/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import javax.swing.JComponent;

/**
 * Builds the panel {@link CatalogEntryEditor}'s "Color Frequency"/"ΔE
 * Heatmap" buttons open, from a decoded {@link ColorImage} — one
 * implementation per chart type ({@link FrequencyBarChart},
 * {@link DeltaEHeatmap}). Purpose-named replacement for a generic
 * {@code java.util.function.Function<ColorImage, JComponent>}.
 */
interface ColorVisualizationFactory {

    JComponent createPanel(ColorImage image);
}
