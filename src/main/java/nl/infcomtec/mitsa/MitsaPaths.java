/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa;

import java.io.File;

/**
 * Resolves MITSA's per-OS config root, following each platform's own
 * convention rather than a flat dotfile in $HOME. Copied from MITSA
 * v1.1.1 (github.com/Walter-Stroebel/mitsa) rather than depended on —
 * MITSA isn't Maven-published, same file-copy convention as
 * jacksonwrap/advswing. Keep in sync by hand if MITSA's own copy changes.
 */
public class MitsaPaths {

    /**
     * Test/override escape hatch: when set, replaces
     * System.getProperty("user.home") everywhere in this class. Needed
     * because the JVM's user.home is read from the OS password database
     * on Linux and does not follow a shell-level $HOME override.
     */
    private static String userHome() {
        String override = System.getenv("MITSA_HOME");
        return (override != null && !override.isEmpty()) ? override : System.getProperty("user.home");
    }

    public static File configDir() {
        String os = System.getProperty("os.name").toLowerCase();
        File dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = new File(appData, "mitsa");
        } else if (os.contains("mac")) {
            dir = new File(userHome(), "Library/Application Support/mitsa");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            File base = (xdg != null && !xdg.isEmpty())
                    ? new File(xdg) : new File(userHome(), ".config");
            dir = new File(base, "mitsa");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create config directory " + dir);
        }
        return dir;
    }

    public static File jarsDir() {
        File dir = new File(configDir(), "jars");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create jars directory " + dir);
        }
        return dir;
    }

    public static File appJarsDir(String appId) {
        File dir = new File(jarsDir(), appId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create jars directory " + dir);
        }
        return dir;
    }

    public static File appsJsonFile() {
        return new File(configDir(), "apps.json");
    }

    public static File lockFile() {
        return new File(configDir(), "tray.lock");
    }

    /**
     * Where MITSA installs its own launcher and per-app shims onto PATH —
     * mirrors the location MITSA's own INSTALL.md step 3 already uses
     * (~/bin/mitsa), so app shims land next to it. Windows apps are
     * expected to add this folder to PATH themselves, same as step 3.
     */
    public static File binDir() {
        File dir = new File(userHome(), "bin");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create bin directory " + dir);
        }
        return dir;
    }

    /**
     * Where a MITSA-managed app should keep its own runtime/data state
     * (catalogs, checkpoints, user documents) — nested under MITSA's own
     * config root ({@code configDir()/data/<appId>}), not scattered
     * across each OS's own separate data-directory convention. Walter's
     * call, 2026-08-19: every MITSA-managed app's footprint on disk
     * should collapse into one shared MITSA environment, not just its
     * launcher/version bookkeeping — the app-data equivalent of
     * jarsDir()/appJarsDir().
     */
    public static File appDataDir(String appId) {
        File dir = new File(new File(configDir(), "data"), appId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Could not create app data directory " + dir);
        }
        return dir;
    }
}
