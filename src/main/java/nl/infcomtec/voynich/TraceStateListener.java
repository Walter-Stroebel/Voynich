/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

/**
 * Notified by {@link ContentAreaCanvas} whenever its traced polygon closes
 * ({@code true}) or is cleared ({@code false}) — drives
 * {@link ContentAreaEditor}'s Commit button enablement. Purpose-named
 * replacement for a generic {@code java.util.function.Consumer<Boolean>}.
 */
interface TraceStateListener {

    void onTraceStateChanged(boolean closed);
}
