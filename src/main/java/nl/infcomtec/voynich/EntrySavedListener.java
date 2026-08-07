/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

/**
 * Notified after {@code entry} is written to the catalog by a
 * {@link CatalogEntryEditor} Save/Done. Purpose-named replacement for a
 * generic {@code java.util.function.Consumer<CatalogEntry>} — "called with
 * the saved entry" belongs in the interface itself, not left for a reader
 * to infer from a standard-library type never designed with this call in
 * mind.
 */
interface EntrySavedListener {

    void onEntrySaved(CatalogEntry entry);
}
