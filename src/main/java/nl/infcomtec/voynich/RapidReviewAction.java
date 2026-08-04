/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

/**
 * One pluggable judgment for {@link RapidReviewWindow}: what accepting the
 * currently displayed image means. The window itself has no idea what a
 * "wash" or any other judgment is — only this interface does — so the same
 * click/skip/abort shell can drive any single-glance yes/no review pass
 * over the catalog, not just this one.
 * <p>
 * Deliberately just a label and a tag template, not a callback: accepting
 * always means "add one tag, built from where the reviewer pointed," so
 * there is nothing task-specific left to run except supplying that
 * template. {@link RapidReviewWindow} owns the actual
 * {@link Catalog#addTag} call.
 */
public interface RapidReviewAction {

    /**
     * @return a short label naming what accepting an image does; shown on
     * the accept button
     */
    String label();

    /**
     * @return a {@link String#format} template for the tag written on
     * accept; takes exactly two {@code %d} arguments, the accepted point's
     * x and y in the original (unscaled) image's pixel coordinates — e.g.
     * {@code "wash@%d,%d"}
     */
    String tagTemplate();
}
