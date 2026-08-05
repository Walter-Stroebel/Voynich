/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

/**
 * One pluggable judgment for {@link CatalogEntryEditor#review}: what
 * clicking the displayed image means. The dialog itself has no idea what a
 * "wash" or any other judgment is — only this interface does — so the same
 * review pass can drive any single-glance tagging task over the catalog,
 * not just this one.
 * <p>
 * Deliberately just a label and a tag template, not a callback: clicking
 * always means "stage one tag, built from where the reviewer pointed," so
 * there is nothing task-specific left to run except supplying that
 * template. {@link CatalogEntryEditor} owns the actual
 * {@link Catalog#save} call.
 */
public interface RapidReviewAction {

    /**
     * @return a short label naming the judgment this review pass records;
     * shown in the dialog title and status line
     */
    String label();

    /**
     * @return a template for the tag staged on a click, with these
     * placeholders substituted from the clicked point:
     * <ul>
     * <li>{@code $X}, {@code $Y} — pixel coordinates in the original
     * (unscaled) image</li>
     * <li>{@code $RGB} — the pixel's colour as {@code r,g,b} (0-255 each)</li>
     * <li>{@code $LAB} — the pixel's colour as CIELAB {@code L,a,b}</li>
     * </ul>
     * e.g. {@code "wash@$X,$Y"} or {@code "wash@$X,$Y $LAB"}
     */
    String tagTemplate();
}
