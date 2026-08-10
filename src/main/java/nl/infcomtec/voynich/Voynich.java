/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.formdev.flatlaf.FlatDarculaLaf;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

/**
 * Entry point. Loads config, validates {@code scanPath}, builds the main
 * {@link JFrame}.
 */
public class Voynich {

    public static final String TITLE = "Voynich tools by InfcomTec";
    /**
     * Base directory for all app state: config, catalog, checkpoints.
     * Created on class load if missing; a pre-existing non-directory at this
     * path is a fatal misconfiguration, not something to work around.
     */
    public static final File baseDir = initBaseDir();
    /**
     * Path to the config file. Defaults to {@code <baseDir>/config.json},
     * overridable via the first CLI argument.
     */
    public static File configFile = new File(baseDir, "config.json");

    private static File initBaseDir() {
        File dir = new File(System.getProperty("user.home"), ".infVoy");
        if (!dir.exists() && !dir.mkdir()) {
            throw new IllegalStateException("Could not create " + dir);
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException(dir + " exists but is not a directory");
        }
        return dir;
    }
    /**
     * The config loaded from {@link #configFile} at startup.
     */
    public static Config config;

    /**
     * Writes {@link #config} back to {@link #configFile}. Called after every
     * change to {@link Config#viewBounds} (see {@link ViewFrame}); an NVMe
     * write on every window move/resize is cheap enough that there's no
     * reason to batch or debounce it.
     */
    public static void saveConfig() {
        try {
            JSON.getMapper().writerWithDefaultPrettyPrinter().writeValue(configFile, config);
        } catch (IOException ex) {
            Logger.getLogger(Voynich.class.getName()).log(Level.WARNING, "Could not save config", ex);
        }
    }

    /**
     * Launches {@link ImageView} as a brand new detached JVM process — not
     * a second {@code JFrame} in this process's own EDT. A user routinely
     * ends up with dozens of these open at once (comparing scans side by
     * side); one shared EDT serving fifty windows' worth of repaint/input
     * events would make all of them janky at once, whereas fifty separate
     * processes each carry their own EDT and can't contend with each
     * other or with this app's own UI. Fire-and-forget: I/O is discarded
     * and the process is never waited on, since this app has no interest
     * in an ImageView window's lifecycle once launched.
     *
     * @param file the image to open, or {@code null} to launch empty
     */
    public static void launchImageView(File file) {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add("nl.infcomtec.voynich.ImageView");
            if (null != file) {
                cmd.add(file.getAbsolutePath());
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (IOException ex) {
            Logger.getLogger(Voynich.class.getName()).log(Level.WARNING, "Could not launch ImageView", ex);
        }
    }

    /**
     * Main.
     *
     * @param args {@code --smokeTest} (anywhere in the args) makes the app
     * exit right after the main {@link JFrame} is constructed, shown, and
     * has completed its first paint, instead of running normally — a CI/
     * scripting-friendly "did it even start" check. Any other, single
     * argument is the path to the configuration file.
     */
    public static void main(String[] args) {
        FlatDarculaLaf.setup();
        boolean smokeTest = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if ("--smokeTest".equals(arg)) {
                smokeTest = true;
            } else {
                positional.add(arg);
            }
        }
        if (!positional.isEmpty()) {
            configFile = new File(positional.get(0));
        }
        config = JSON.readValue(null, configFile, Config.class);
        if (null == config || null == config.scanPath) {
            System.err.format("%s does not exist or no base scan path set.\n", configFile);
            config = new Config();
            config.scanPath = "set this to your collection of Voynich scans";
            System.err.println(JSON.writeValueAsPretty(config));
            System.exit(2);
        }

        Catalog catalog;
        try {
            catalog = Catalog.open(config);
        } catch (IOException ex) {
            System.err.println("Could not open catalog: " + ex.getMessage());
            System.exit(2);
            return;
        }

        final OverviewPanel overview = new OverviewPanel(catalog);
        try {
            overview.loadFromCatalog();
        } catch (IOException ex) {
            System.err.println("Could not load catalog: " + ex.getMessage());
        }

        final JFrame fr = new JFrame(TITLE);
        fr.setExtendedState(JFrame.MAXIMIZED_BOTH);
        fr.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel outer = new JPanel(new BorderLayout());
        JToolBar toolBar = new JToolBar();
        // Acquisition.
        toolBar.add(new JButton(new EzAction("Scan") {
            @Override
            public void actionPerformed(ActionEvent e) {
                TaskWindow existing = TaskWindow.getOrNull(ScanTaskWindow.TASK_TYPE);
                if (null == existing) {
                    new ScanTaskWindow(config, catalog, overview, fr).start();
                } else {
                    existing.start();
                }
            }
        }.withTooltip("Walk the configured scan folder and catalog anything new or changed")));
        toolBar.addSeparator();
        // Grid view/organize.
        toolBar.add(new JButton(new EzAction("Sort") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.sort();
            }
        }.withTooltip("Re-order the thumbnail grid by filename, page number, size, colour, or content-area size")));
        toolBar.add(new JButton(new EzAction("Filter") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.filter();
            }
        }.withTooltip("Show only entries whose full JSON record matches (or, inverted, doesn't match) some text")));
        toolBar.add(new JToggleButton(new EzAction("Content Area Only") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.setContentAreaOnly(((JToggleButton) e.getSource()).isSelected());
            }
        }.withTooltip("Dim every thumbnail down to just its traced main content area")));
        toolBar.addSeparator();
        // Review/tagging.
        JTextField markupTemplate = new JTextField("was@$X,$Y", 20);
        markupTemplate.setMaximumSize(markupTemplate.getPreferredSize());
        markupTemplate.setToolTipText("<html>Tag template for MarkUp review. Placeholders:<br>"
                + "$X, $Y — clicked pixel, original image coordinates<br>"
                + "$RGB — clicked pixel's colour as r,g,b<br>"
                + "$LAB — clicked pixel's colour as CIELAB L,a,b</html>");
        toolBar.add(new JButton(new EzAction("MarkUp") {
            @Override
            public void actionPerformed(ActionEvent e) {
                String template = markupTemplate.getText();
                try {
                    CatalogEntryEditor.review(fr, catalog, new RapidReviewAction() {
                        @Override
                        public String label() {
                            return "MarkUp";
                        }

                        @Override
                        public String tagTemplate() {
                            return template;
                        }
                    }, new EntrySavedListener() {
                        @Override
                        public void onEntrySaved(CatalogEntry saved) {
                            overview.addOrUpdate(saved);
                        }
                    });
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(fr, "Could not start review: " + ex.getMessage(),
                            "Review failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.withTooltip("Shuffle through every cataloged entry, clicking the image to stage a tag from the template")));
        toolBar.add(markupTemplate);
        toolBar.addSeparator();
        // Whole-catalog safety net.
        toolBar.add(new JButton(new EzAction("Storage") {
            @Override
            public void actionPerformed(ActionEvent e) {
                StorageDialog.open(fr, catalog, new Runnable() {
                    @Override
                    public void run() {
                        reloadOverview(fr, overview);
                    }
                });
            }
        }.withTooltip("View/take/restore/delete whole-catalog checkpoints")));
        toolBar.addSeparator();
        // Session control.
        toolBar.add(new JButton(new EzAction("Exit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        }.withTooltip("Quit the application")));
        outer.add(toolBar, BorderLayout.NORTH);
        outer.add(overview, BorderLayout.CENTER);
        fr.setContentPane(outer);
        final boolean doSmokeTest = smokeTest;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fr.setVisible(true);
                if (doSmokeTest) {
                    scheduleSmokeTestExit();
                }
            }
        });
    }

    /**
     * Reloads {@link OverviewPanel} from the catalog, e.g. after a
     * {@link StorageDialog} restore replaces the live catalog wholesale
     * out from under it.
     */
    private static void reloadOverview(JFrame fr, OverviewPanel overview) {
        try {
            overview.loadFromCatalog();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(fr, "Could not reload catalog: " + ex.getMessage(),
                    "Reload failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Two nested {@link SwingUtilities#invokeLater} hops after
     * {@code setVisible(true)} — which already queued the frame's first
     * paint on the EDT — run {@link #printSmokeTestOkAndExit()} after that
     * paint (and any repaint it triggers) has actually executed, not just
     * been requested.
     */
    private static void scheduleSmokeTestExit() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        printSmokeTestOkAndExit();
                    }
                });
            }
        });
    }

    private static void printSmokeTestOkAndExit() {
        System.out.println("Smoke test OK: " + TITLE + " constructed and painted.");
        System.exit(0);
    }
}
