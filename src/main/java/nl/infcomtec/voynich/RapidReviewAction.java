/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.io.IOException;

/**
 * One pluggable judgment for {@link RapidReviewWindow}: what accepting the
 * currently displayed image means. The window itself has no idea what a
 * "wash" or any other judgment is — only this interface does — so the same
 * click/skip/abort shell can drive any single-glance yes/no review pass
 * over the catalog, not just this one.
 */
public interface RapidReviewAction {

    /**
     * @return a short label naming what accepting an image does; shown on
     * the accept button
     */
    String label();

    /**
     * Called when the reviewer accepts the currently displayed entry.
     *
     * @param entry the entry being reviewed
     * @throws IOException if recording the judgment fails
     */
    void onAccept(CatalogEntry entry) throws IOException;
}
