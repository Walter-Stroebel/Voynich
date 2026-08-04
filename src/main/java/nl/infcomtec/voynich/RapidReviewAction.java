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
     * @return a {@link String#format} template for the tag staged on a
     * click; takes exactly two {@code %d} arguments, the clicked point's x
     * and y in the original (unscaled) image's pixel coordinates — e.g.
     * {@code "wash@%d,%d"}
     */
    String tagTemplate();
}
