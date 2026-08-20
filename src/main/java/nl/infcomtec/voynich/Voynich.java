/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.formdev.flatlaf.FlatDarculaLaf;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import nl.infcomtec.mitsa.MitsaPaths;
import nl.infcomtec.tools.GetOpt;
import nl.infcomtec.tools.Option;

/**
 * Entry point. Loads config, validates {@code scanPath}, builds the main
 * {@link JFrame}.
 */
public class Voynich {

    public static final String TITLE = "Voynich tools by InfcomTec";
    /**
     * The default MITSA appId, and default {@code --identity}. A second
     * identity (e.g. {@code "voynich-clean"}, pointed at a denoised copy
     * of the scans) gets its own {@link #baseDir} — own config, catalog,
     * and checkpoints — entirely separate from this one; nothing is
     * shared between identities except by explicit Export/Import (see
     * {@code CatalogExporter}/{@code CatalogImporter}), never a shared
     * catalog directory. Walter's call, 2026-08-20: sharing one catalog
     * across two scan sources "will bite us on the derriere."
     */
    public static final String DEFAULT_IDENTITY = "voynich";
    /**
     * The identity this process is running as — the MITSA appId passed to
     * {@link MitsaPaths#appDataDir(String)} for {@link #baseDir}. Set once
     * in {@link #main} before {@link #baseDir}/{@link #configFile} are
     * resolved; not reassigned afterward.
     */
    public static String identity = DEFAULT_IDENTITY;
    /**
     * Base directory for all app state: config, catalog, checkpoints.
     * Resolved via {@link MitsaPaths#appDataDir(String)} keyed by
     * {@link #identity} — nested under MITSA's own config root, not a
     * standalone dotfile in $HOME. Not {@code final}: depends on
     * {@link #identity}, known only once {@link #main} parses
     * {@code --identity}.
     */
    public static File baseDir = MitsaPaths.appDataDir(DEFAULT_IDENTITY);
    /**
     * Path to the config file. Defaults to {@code <baseDir>/config.json},
     * overridable via {@code -c}/{@code --config}.
     */
    public static File configFile = new File(baseDir, "config.json");
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
     * Launches the standalone infimg viewer (see
     * {@link Config#infimgJar}) as a brand new detached JVM process — not
     * an in-process window. A user routinely ends up with dozens of these
     * open at once (comparing scans side by side); one shared EDT serving
     * fifty windows' worth of repaint/input events would make all of them
     * janky at once, whereas fifty separate processes each carry their own
     * EDT and can't contend with each other or with this app's own UI.
     * Fire-and-forget: I/O is discarded and the process is never waited
     * on, since this app has no interest in the viewer window's lifecycle
     * once launched. Pops a {@link JOptionPane} (not just a log line — the
     * old warning-only version was itself the "silent backup failure"
     * anti-pattern this project already knows to avoid) if
     * {@link Config#infimgJar} isn't set, since both call sites
     * ({@code RegionViewer}'s "Save to /tmp & View" button and
     * {@code CatalogCli}'s {@code --view}) run on a machine with a display.
     *
     * @param file the image to open, or {@code null} to launch empty
     */
    public static void launchImageView(File file) {
        launchImageView(null == file ? Collections.<File>emptyList() : Collections.singletonList(file));
    }

    /**
     * Same as {@link #launchImageView(File)}, but hands infimg every file in
     * {@code files} on one command line — infimg already treats all trailing
     * positional arguments as a navigable set (its Prev/Next buttons step
     * through them), so a multi-selection from {@link OverviewPanel}'s
     * "Selected" menu opens as one browsable session rather than N separate
     * windows.
     *
     * @param files the images to open, in the order infimg's Prev/Next
     * should step through; empty launches infimg with nothing loaded
     */
    public static void launchImageView(List<File> files) {
        if (null == config.infimgJar) {
            Logger.getLogger(Voynich.class.getName()).log(Level.WARNING,
                    "Config.infimgJar is not set; cannot launch infimg");
            JOptionPane.showMessageDialog(null, "No viewer selected, see manual",
                    "Cannot open viewer", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(config.infimgJar);
            for (File file : files) {
                cmd.add(file.getAbsolutePath());
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (IOException ex) {
            Logger.getLogger(Voynich.class.getName()).log(Level.WARNING, "Could not launch infimg", ex);
            JOptionPane.showMessageDialog(null, "Could not launch infimg:\n" + ex.getMessage(),
                    "Cannot open viewer", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Main.
     *
     * @param args {@code -c}/{@code --config-file FILE} overrides
     * {@link #configFile} directly. {@code --identity NAME} selects which
     * MITSA appId (and therefore which {@link #baseDir} — separate
     * catalog/checkpoints, not just a different config file) this process
     * runs as; its default {@link #configFile} is {@code <baseDir>/config.json}
     * for that identity, still overridable by {@code --config-file}.
     * {@code --smokeTest} makes the app exit right after the main
     * {@link JFrame} is constructed, shown, and has completed its first
     * paint, instead of running normally — a CI/scripting-friendly "did it
     * even start" check, not a user-facing feature.
     */
    public static void main(String[] args) {
        FlatDarculaLaf.setup();
        Option[] extra = new Option[]{
            new Option((char) 0, "smokeTest", null, "Exit right after first paint (for CI).", null),
            new Option((char) 0, "identity", "NAME", "MITSA appId to run as (own config/catalog/checkpoints); default \"" + DEFAULT_IDENTITY + "\".", null)
        };
        GetOpt opts = new GetOpt(args, "Voynich", extra, null);
        Option identityOpt = opts.getOption("identity");
        Option configOpt = opts.getOption("config-file");
        boolean smokeTest = opts.getOption("smokeTest") != null;
        if (identityOpt != null) {
            identity = identityOpt.value;
            baseDir = MitsaPaths.appDataDir(identity);
            configFile = new File(baseDir, "config.json");
        }
        if (configOpt != null) {
            configFile = new File(configOpt.value);
        }
        config = JSON.readValue(null, configFile, Config.class);
        if (null == config || null == config.scanPath) {
            System.err.format("%s does not exist or no base scan path set.\n", configFile);
            config = new Config();
            config.scanPath = "set this to your collection of Voynich scans";
            System.err.println(JSON.writeValueAsPretty(config));
            System.exit(2);
        }

        // The naming table is every catalog entry's identity ground truth
        // (see CatalogEntry.id's doc) — a missing bundled resource just
        // disables the "Rename to..." menu (see below), but a malformed one
        // (e.g. ScanRenamer.load's duplicate-value check) means id
        // resolution itself is untrustworthy app-wide, not just for
        // renaming, so that case is a hard stop before anything else loads.
        try {
            ScanRenamer.cached();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "The bundled naming table (data/scan-naming.tsv) is malformed:\n"
                    + ex.getMessage() + "\n\nFix the table before running this app.",
                    "Cannot start", JOptionPane.ERROR_MESSAGE);
            System.err.println("Cannot start: " + ex.getMessage());
            System.exit(2);
            return;
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
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.add(new JMenuItem(new EzAction("Scan") {
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
        JMenu renameMenu = new JMenu("Rename to…");
        final Map<String, JMenuItem> renameItemsByColumn = new java.util.LinkedHashMap<>();
        try {
            ScanRenamer renamer = ScanRenamer.cached();
            for (final String column : renamer.columns) {
                JMenuItem item = new JMenuItem(new EzAction(column) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        renameScans(fr, catalog, overview, column);
                    }
                });
                renameItemsByColumn.put(column, item);
                renameMenu.add(item);
            }
        } catch (IOException ex) {
            JMenuItem failed = new JMenuItem("(failed to load naming table: " + ex.getMessage() + ")");
            failed.setEnabled(false);
            renameMenu.add(failed);
        }
        // Built once, items never rebuilt — only the current scheme's item
        // is disabled/re-enabled here on each menu open. Rebuilding the
        // list from config.namingScheme on every open was the earlier bug:
        // a stale config value (not yet corrected by renameScans' own
        // detection) could leave the *actual* current scheme still
        // clickable, letting a user "rename" a scheme to itself.
        renameMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                for (Map.Entry<String, JMenuItem> entry : renameItemsByColumn.entrySet()) {
                    entry.getValue().setEnabled(!entry.getKey().equals(config.namingScheme));
                }
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        fileMenu.add(renameMenu);
        JMenu exportMenu = new JMenu("Export…");
        exportMenu.add(new JMenuItem(new EzAction("All") {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    exportEntries(fr, catalog.listAll());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(fr, "Could not read catalog:\n" + ex.getMessage(),
                            "Export failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.withTooltip("Export metadata (tags/regions) for every catalog entry")));
        exportMenu.add(new JMenuItem(new EzAction("Selected") {
            @Override
            public void actionPerformed(ActionEvent e) {
                exportEntries(fr, overview.getSelectedEntries());
            }
        }.withTooltip("Export metadata (tags/regions) for the currently selected thumbnails")));
        exportMenu.add(new JMenuItem(new EzAction("Marked") {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    exportEntries(fr, CatalogExporter.marked(catalog));
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(fr, "Could not read catalog:\n" + ex.getMessage(),
                            "Export failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.withTooltip("Export metadata only for entries with real tags/regions beyond the whole page")));
        fileMenu.add(exportMenu);
        fileMenu.add(new JMenuItem(new EzAction("Import…") {
            @Override
            public void actionPerformed(ActionEvent e) {
                importEntries(fr, catalog, overview);
            }
        }.withTooltip("Review and selectively add another researcher's exported tags/regions")));
        fileMenu.add(new JMenuItem(new EzAction("Storage…") {
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
        fileMenu.add(new JMenuItem(new EzAction("Switch Identity…") {
            @Override
            public void actionPerformed(ActionEvent e) {
                switchIdentity(fr);
            }
        }.withTooltip("Restart as a different identity (own config/catalog/checkpoints), e.g. to point at cleaned scans")));
        fileMenu.addSeparator();
        fileMenu.add(new JMenuItem(new EzAction("Exit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        }.withTooltip("Quit the application")));
        menuBar.add(fileMenu);

        JMenu editMenu = new JMenu("Edit");
        editMenu.add(new JMenuItem(new EzAction("Select All") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.selectAllEntries();
            }
        }.withTooltip("Select every thumbnail currently shown in the grid")));
        editMenu.add(new JMenuItem(new EzAction("Clear Selection") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.clearEntrySelection();
            }
        }.withTooltip("Deselect all thumbnails")));
        menuBar.add(editMenu);

        JMenu viewMenu = new JMenu("View");
        viewMenu.add(new JMenuItem(new EzAction("Sort") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.sort();
            }
        }.withTooltip("Re-order the thumbnail grid by filename, page number, size, colour, or content-area size")));
        viewMenu.add(new JMenuItem(new EzAction("Filter") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.filter();
            }
        }.withTooltip("Show only entries whose full JSON record matches (or, inverted, doesn't match) some text")));
        JCheckBoxMenuItem contentAreaOnly = new JCheckBoxMenuItem(new EzAction("Content Area Only") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.setContentAreaOnly(((JCheckBoxMenuItem) e.getSource()).isSelected());
            }
        }.withTooltip("Dim every thumbnail down to just its traced main content area"));
        viewMenu.add(contentAreaOnly);
        menuBar.add(viewMenu);

        JMenu reviewMenu = new JMenu("Review");
        reviewMenu.add(new JMenuItem(new EzAction("MarkUp…") {
            @Override
            public void actionPerformed(ActionEvent e) {
                JTextField markupTemplate = new JTextField("was@$X,$Y", 20);
                markupTemplate.setToolTipText("<html>Tag template for MarkUp review. Placeholders:<br>"
                        + "$X, $Y — clicked pixel, original image coordinates<br>"
                        + "$RGB — clicked pixel's colour as r,g,b<br>"
                        + "$LAB — clicked pixel's colour as CIELAB L,a,b</html>");
                JPanel prompt = new JPanel(new BorderLayout(4, 4));
                prompt.add(new JLabel("Tag template:"), BorderLayout.WEST);
                prompt.add(markupTemplate, BorderLayout.CENTER);
                int choice = JOptionPane.showConfirmDialog(fr, prompt, "Start MarkUp review",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (JOptionPane.OK_OPTION != choice) {
                    return;
                }
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
        menuBar.add(reviewMenu);

        JMenu selectedMenu = new JMenu("Selected");
        JMenuItem freqItem = new JMenuItem(new EzAction("Color Frequency") {
            @Override
            public void actionPerformed(ActionEvent e) {
                CatalogEntry entry = overview.getSelectedEntries().get(0);
                RegionView.openColorVisualization(fr, entry, entry.mainRegion(), "Color Frequency",
                        new ColorVisualizationFactory() {
                            @Override
                            public JComponent createPanel(ColorImage ci) {
                                return new JScrollPane(new FrequencyBarChart(ci));
                            }
                        });
            }
        }.withTooltip("Ranked colour-frequency chart for the selected page's main content area (or whole page)"));
        JMenuItem heatItem = new JMenuItem(new EzAction("ΔE Heatmap") {
            @Override
            public void actionPerformed(ActionEvent e) {
                CatalogEntry entry = overview.getSelectedEntries().get(0);
                RegionView.openColorVisualization(fr, entry, entry.mainRegion(), "ΔE Heatmap",
                        new ColorVisualizationFactory() {
                            @Override
                            public JComponent createPanel(ColorImage ci) {
                                return new DeltaEHeatmap(ci);
                            }
                        });
            }
        }.withTooltip("Spatial colour-distance-from-average map for the selected page's main content area (or whole page)"));
        JMenuItem visionItem = new JMenuItem(new EzAction("Ask Vision…") {
            @Override
            public void actionPerformed(ActionEvent e) {
                List<CatalogEntry> selected = overview.getSelectedEntries();
                String question = JOptionPane.showInputDialog(fr,
                        "Question for the vision model:", "Ask Vision", JOptionPane.PLAIN_MESSAGE);
                if (null == question || question.isBlank()) {
                    return;
                }
                if (1 == selected.size()) {
                    CatalogEntry entry = selected.get(0);
                    RegionView.askVision(fr, entry, entry.mainRegion(), question);
                } else if (2 == selected.size()) {
                    askVisionOnPair(fr, selected.get(0), selected.get(1), question);
                } else {
                    int choice = JOptionPane.showConfirmDialog(fr,
                            "Ask the vision model about " + selected.size() + " images ("
                            + selected.size() + " calls)?",
                            "Ask many images?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (JOptionPane.YES_OPTION == choice) {
                        askVisionSequentially(fr, selected, 0, question);
                    }
                }
            }
        }.withTooltip("Ask the local vision model a free-text question about the selected page(s)"));
        JMenuItem infimgItem = new JMenuItem(new EzAction("Open in infimg") {
            @Override
            public void actionPerformed(ActionEvent e) {
                List<CatalogEntry> selected = overview.getSelectedEntries();
                if (1 == selected.size()) {
                    CatalogEntry entry = selected.get(0);
                    RegionView.openInInfimg(fr, entry, entry.mainRegion());
                } else {
                    if (selected.size() > 12) {
                        int choice = JOptionPane.showConfirmDialog(fr,
                                "Open " + selected.size() + " images in infimg?",
                                "Open many images?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (JOptionPane.YES_OPTION != choice) {
                            return;
                        }
                    }
                    List<File> files = new ArrayList<>();
                    for (CatalogEntry entry : selected) {
                        File file = ImageDisplay.pickExistingFile(entry);
                        if (null != file) {
                            files.add(file);
                        }
                    }
                    Voynich.launchImageView(files);
                }
            }
        }.withTooltip("Open the selected page(s) full-resolution in infimg"));
        JMenuItem twoPageItem = new JMenuItem(new EzAction("Two-Page View") {
            @Override
            public void actionPerformed(ActionEvent e) {
                List<CatalogEntry> pair = twoPagePair(overview);
                if (null == pair) {
                    return;
                }
                openTwoPageView(fr, pair.get(0), pair.get(1), overview.isContentAreaOnly());
            }
        }.withTooltip("Compose the selected page and its r/v counterpart side by side, and open the result in infimg"));
        JMenuItem matrixItem = new JMenuItem(new EzAction("Thumbnail Matrix") {
            @Override
            public void actionPerformed(ActionEvent e) {
                openThumbnailMatrix(fr, overview, overview.getSelectedEntries());
            }
        }.withTooltip("Compose the selected pages' thumbnails into one grid image, and open the result in infimg"));
        selectedMenu.add(freqItem);
        selectedMenu.add(heatItem);
        selectedMenu.add(visionItem);
        selectedMenu.add(infimgItem);
        selectedMenu.addSeparator();
        selectedMenu.add(twoPageItem);
        selectedMenu.add(matrixItem);
        selectedMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                int count = overview.getSelectedEntries().size();
                freqItem.setEnabled(1 == count);
                heatItem.setEnabled(1 == count);
                visionItem.setEnabled(count >= 1);
                infimgItem.setEnabled(count >= 1);
                twoPageItem.setEnabled(null != twoPagePair(overview));
                matrixItem.setEnabled(count >= 1);
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        menuBar.add(selectedMenu);

        menuBar.add(Box.createHorizontalGlue());
        BusyIndicator busyIndicator = new BusyIndicator(fileMenu.getPreferredSize().height);
        menuBar.add(busyIndicator);
        RegionView.busy = busyIndicator;

        fr.setJMenuBar(menuBar);
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
     * File → "Rename to…" &lt;column&gt; handler: plans the rename via
     * {@link ScanRenamer#plan}, warns on known-oddity file counts (see
     * below) and any planned skips before touching anything, then runs it
     * via {@link RenameTaskWindow} and re-triggers a Scan afterward so
     * {@link OverviewPanel} picks up the new filenames — a rename alone
     * only touches the filesystem, not the catalog, which is still keyed by
     * the old names until Scan reconciles it.
     * <p>
     * The file-count guardrail exists because {@link Config#scanPath}
     * pointing at the wrong directory (an old export, a stray subfolder, a
     * completely unrelated folder) is exactly the kind of mistake an
     * in-place rename should catch before acting on it, not after: 213 is
     * the known-correct manuscript page count (see {@code SCANS.md}), so a
     * handful of files (e.g. ≤10) is almost certainly not a real scan set,
     * and more than 213 means something unexpected is mixed in.
     * </p>
     */
    private static void renameScans(final JFrame fr, final Catalog catalog, final OverviewPanel overview, String toColumn) {
        ScanRenamer renamer;
        try {
            renamer = ScanRenamer.load();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(fr, "Could not load naming table: " + ex.getMessage(),
                    "Rename failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File scanDir = new File(config.scanPath);
        File[] listing = scanDir.listFiles();
        int fileCount = null == listing ? 0 : listing.length;
        if (fileCount <= 10) {
            int choice = JOptionPane.showConfirmDialog(fr,
                    "Only " + fileCount + " file(s) found in " + config.scanPath
                    + " — this doesn't look like the 213-page manuscript set. Continue anyway?",
                    "Unexpectedly few files", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        } else if (fileCount > 213) {
            int choice = JOptionPane.showConfirmDialog(fr,
                    fileCount + " files found in " + config.scanPath
                    + " — expected 213. Continue anyway?",
                    "Unexpectedly many files", JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        String fromColumn = confirmCurrentNamingScheme(fr, renamer, scanDir, fileCount);
        if (null == fromColumn) {
            return;
        }

        List<ScanRenamer.Plan> plans = renamer.plan(scanDir, fromColumn, toColumn);
        int skipCount = 0;
        for (ScanRenamer.Plan p : plans) {
            if (null != p.skipReason) {
                skipCount++;
            }
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Rename ").append(plans.size() - skipCount).append(" file(s) in ")
                .append(config.scanPath).append(" from \"").append(fromColumn)
                .append("\" to \"").append(toColumn).append("\" naming, in place.\n");
        if (skipCount > 0) {
            msg.append(skipCount).append(" file(s) will be skipped (no target name, or a collision).\n");
        }
        msg.append("\nIf this folder is watched by a backup or sync tool, an in-place rename may "
                + "register as deletes+new-files rather than a rename.");
        int choice = JOptionPane.showConfirmDialog(fr, msg.toString(), "Confirm rename",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        // Deliberately never reused via TaskWindow.getOrNull, unlike Scan:
        // each rename carries its own plans/toColumn baked in at
        // construction, and TaskWindow.start() on an existing instance
        // re-runs its ORIGINAL runTask() closure, not a fresh one — reusing
        // the window here would silently replay a stale rename plan
        // against whatever the new target scheme was clicked, not the
        // scheme actually requested. A prior rename's window, if still
        // open, is simply left alone; this one always opens as a new
        // window.
        new RenameTaskWindow(config, catalog, overview, plans, toColumn, fr).start();
    }

    /**
     * Sanity-checks {@link Config#namingScheme} against what's actually in
     * {@code scanDir} before trusting it as {@code fromColumn} for a
     * rename — {@code namingScheme} defaults to {@code "Sequential"} for
     * every config, including ones (like an already-existing PNG working
     * set) that were never torrent-named to begin with, so a stale/wrong
     * default must be caught here rather than silently producing a
     * zero-file rename plan. Scores every naming column via
     * {@link ScanRenamer#detectScheme} and only asks the user when the
     * configured scheme's match count looks wrong (fewer than half the
     * files present) and some other column fits meaningfully better;
     * otherwise trusts the configured value without bothering the user.
     *
     * @return the naming scheme to actually use as {@code fromColumn}, or
     * {@code null} if the user cancelled out of a correction prompt
     */
    private static String confirmCurrentNamingScheme(JFrame fr, ScanRenamer renamer, File scanDir, int fileCount) {
        Map<String, Integer> counts = renamer.detectScheme(scanDir);
        Integer configuredCount = counts.get(config.namingScheme);
        int configured = null == configuredCount ? 0 : configuredCount;
        if (fileCount > 0 && configured * 2 >= fileCount) {
            // Configured scheme accounts for at least half the files present — trust it.
            return config.namingScheme;
        }
        String bestColumn = config.namingScheme;
        int bestCount = configured;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestColumn = e.getKey();
                bestCount = e.getValue();
            }
        }
        if (bestColumn.equals(config.namingScheme) || bestCount == 0) {
            // No better fit found — proceed with what's configured and let
            // the rename plan itself report zero matches, rather than
            // guessing at a scheme that fits just as poorly.
            return config.namingScheme;
        }
        int choice = JOptionPane.showConfirmDialog(fr,
                "Configured naming scheme is \"" + config.namingScheme + "\", but only " + configured
                + " of " + fileCount + " file(s) in " + scanDir + " match it.\n"
                + "\"" + bestColumn + "\" matches " + bestCount + " of them instead — use that as the "
                + "current naming and update the saved setting?",
                "Naming scheme looks wrong", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return config.namingScheme;
        }
        config.namingScheme = bestColumn;
        saveConfig();
        return bestColumn;
    }

    /**
     * File → Export…'s shared behavior for All/Selected/Marked: prompts for
     * an exporter name (used to attribute any blank {@link
     * CatalogEntry.Region#author} in the export only — never written back to
     * the catalog, see {@link CatalogExporter#toExported}), then a save
     * dialog for the output JSON. No-ops silently on an empty {@code
     * entries} or a cancelled prompt/dialog.
     */
    private static void exportEntries(JFrame fr, List<CatalogEntry> entries) {
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(fr, "Nothing to export.", "Export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String exporterName = JOptionPane.showInputDialog(fr,
                "Exporter name (used to attribute unattributed regions in this export only):",
                "Export", JOptionPane.QUESTION_MESSAGE);
        if (null == exporterName) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        // .zip offered first/default: this data (repeated JSON keys, vertex
        // coordinates) compresses heavily — see CatalogExporter.export's doc.
        // Plain .json stays available as a filter choice for anyone who wants
        // to read the export directly in a text editor.
        FileNameExtensionFilter zipFilter = new FileNameExtensionFilter("Zip archive (.zip)", "zip");
        FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("Plain JSON (.json)", "json");
        chooser.addChoosableFileFilter(jsonFilter);
        chooser.addChoosableFileFilter(zipFilter);
        chooser.setFileFilter(zipFilter);
        chooser.setSelectedFile(new File("voynich-export.zip"));
        if (JFileChooser.APPROVE_OPTION != chooser.showSaveDialog(fr)) {
            return;
        }
        File target = chooser.getSelectedFile();
        String lower = target.getName().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip") && !lower.endsWith(".json")) {
            boolean wantsZip = chooser.getFileFilter() == zipFilter;
            target = new File(target.getParentFile(), target.getName() + (wantsZip ? ".zip" : ".json"));
        }
        try {
            CatalogExporter.export(entries, exporterName, target);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(fr, "Export failed:\n" + ex.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * File → Import…'s entry point: picks the exported file (plain JSON or
     * zip, see {@link CatalogImporter#load}), loads and
     * classifies it against the local catalog (see {@link
     * CatalogImporter#classify}), reports any unresolvable records plainly
     * rather than dropping them silently, takes a whole-catalog {@link
     * Catalog#checkpoint()} as a one-click undo before anything is written
     * (import is a review-and-accept flow, but a run of accidental clicks
     * is still easiest undone via Storage… → Restore, same safety net every
     * other bulk operation in this app gets), then opens {@link
     * ImportReviewDialog}.
     */
    private static void importEntries(JFrame fr, Catalog catalog, OverviewPanel overview) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Voynich export (.zip, .json)", "zip", "json"));
        if (JFileChooser.APPROVE_OPTION != chooser.showOpenDialog(fr)) {
            return;
        }
        List<CatalogExporter.Exported> records;
        try {
            records = CatalogImporter.load(chooser.getSelectedFile());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(fr, "Could not read import file:\n" + ex.getMessage(),
                    "Import failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        CatalogImporter.Classified classified;
        try {
            classified = CatalogImporter.classify(catalog, records);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(fr, "Could not read catalog:\n" + ex.getMessage(),
                    "Import failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!classified.unresolvable.isEmpty()) {
            JOptionPane.showMessageDialog(fr,
                    "Not importable:\n" + String.join("\n", classified.unresolvable),
                    "Some records could not be resolved", JOptionPane.WARNING_MESSAGE);
        }
        if (classified.resolvable.isEmpty()) {
            JOptionPane.showMessageDialog(fr, "Nothing importable in this file.",
                    "Import", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            catalog.checkpoint();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(fr, "Could not take a safety checkpoint before import:\n"
                    + ex.getMessage() + "\n\nImport cancelled.", "Import failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        ImportReviewDialog.open(fr, catalog, overview, classified.resolvable);
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
     * Prompts for an identity name, then relaunches this app as that
     * identity (own {@link #baseDir} — config, catalog, checkpoints, all
     * separate) via MITSA's own {@code mitsa run voynich --identity NAME}
     * (the same launch path the {@code voynich} shim on PATH and MITSA's
     * tray already use — MITSA is this app's defined ecosystem, not just
     * an optional installer), then exits this instance once the new one
     * has actually started. No data of any kind (catalog, tags, regions)
     * transfers automatically between identities — see Import… for the
     * explicit, human-reviewed path for that.
     */
    private static void switchIdentity(JFrame fr) {
        String name = JOptionPane.showInputDialog(fr,
                "Identity to run as (own config/catalog/checkpoints):",
                identity);
        if (null == name || name.isBlank() || name.equals(identity)) {
            return;
        }
        try {
            new ProcessBuilder("mitsa", "run", "voynich", "--identity", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(fr, "Could not launch identity \"" + name + "\": " + ex.getMessage(),
                    "Switch Identity failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        System.exit(0);
    }

    /**
     * @return the verso+recto pair (left-hand verso first, right-hand recto
     * second — the order an open book spread actually reads: verso is the
     * back of the previous leaf, sitting on the left; recto is the front of
     * the current leaf, on the right) for "Selected &gt; Two-Page View" —
     * either the current selection itself if it's exactly two entries that
     * both parse as a {@link OverviewPanel.Folio}, or, for a single
     * selected entry, that entry plus its inferred exact-filename
     * counterpart if it exists in the catalog. {@code null} for any other
     * shape (0, 3+, an unparseable filename, or a 1-selection whose
     * counterpart isn't cataloged) — a disabled menu item, not a nag, since
     * this is a shape mismatch rather than a scale risk. Non-foliated pages
     * (covers, flyleaves) never parse, so they never offer this action.
     */
    private static List<CatalogEntry> twoPagePair(OverviewPanel overview) {
        List<CatalogEntry> selected = overview.getSelectedEntries();
        if (2 == selected.size()) {
            OverviewPanel.Folio a = OverviewPanel.parseFolio(selected.get(0));
            OverviewPanel.Folio b = OverviewPanel.parseFolio(selected.get(1));
            if (null == a || null == b) {
                return null;
            }
            boolean aIsVerso = 'v' == a.side;
            return aIsVerso ? List.of(selected.get(0), selected.get(1)) : List.of(selected.get(1), selected.get(0));
        }
        if (1 == selected.size()) {
            CatalogEntry entry = selected.get(0);
            OverviewPanel.Folio folio = OverviewPanel.parseFolio(entry);
            if (null == folio) {
                return null;
            }
            char otherSide = 'r' == folio.side ? 'v' : 'r';
            CatalogEntry other = overview.findFolioCounterpart(folio.number, otherSide);
            if (null == other) {
                return null;
            }
            return 'v' == folio.side ? List.of(entry, other) : List.of(other, entry);
        }
        return null;
    }

    /**
     * "Selected &gt; Ask Vision…" for exactly 2 selected entries: offers a
     * real choice, since two pages side by side is a coherent single
     * composite (the same reasoning Two-Page View already applies) — asks
     * whether to send one combined image (1 vision call) or ask about each
     * separately (2 calls), confirms the resulting call count either way
     * (vision calls are the one genuinely expensive action in this app —
     * always confirm intent above N=1, never infer it from a bare count),
     * then fires accordingly.
     */
    private static void askVisionOnPair(JFrame fr, CatalogEntry a, CatalogEntry b, String question) {
        Object[] options = {"Combined", "Separate", "Cancel"};
        int choice = JOptionPane.showOptionDialog(fr,
                "Send the 2 selected pages as one combined image (1 vision call), "
                + "or ask about each separately (2 vision calls)?",
                "Ask Vision on 2 pages", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (0 == choice) {
            File fileA = ImageDisplay.pickExistingFile(a);
            File fileB = ImageDisplay.pickExistingFile(b);
            if (null == fileA || null == fileB) {
                return;
            }
            RegionView.busy.enter();
            new SwingWorker<File, Void>() {
                @Override
                protected File doInBackground() throws IOException {
                    // Vision models have a real pixel/upload budget no server implementation
                    // survives exceeding (see VisionClient's MAX_DIMENSION clamp) — unlike
                    // Two-Page View's infimg composite (full-resolution, meant for local
                    // viewing/saving), a composite headed for the vision model must already be
                    // small when built, not rely on a post-hoc resize after decoding a huge file.
                    int cellCap = VisionClient.MAX_DIMENSION / 2;
                    BufferedImage imgA = ImageDisplay.scaleToFit(ImageIO.read(fileA), cellCap, cellCap);
                    BufferedImage imgB = ImageDisplay.scaleToFit(ImageIO.read(fileB), cellCap, cellCap);
                    int cellW = Math.max(imgA.getWidth(), imgB.getWidth());
                    int cellH = Math.max(imgA.getHeight(), imgB.getHeight());
                    BufferedImage composite = ImageGrid.paint(List.of(imgA, imgB), 2, new Dimension(cellW, cellH));
                    File target = new File(System.getProperty("java.io.tmpdir"),
                            OverviewPanel.displayNameOf(a) + "+" + OverviewPanel.displayNameOf(b)
                                    + ".combined." + System.currentTimeMillis() + ".png");
                    ImageIO.write(composite, "png", target);
                    return target;
                }

                @Override
                protected void done() {
                    RegionView.busy.exit();
                    try {
                        RegionView.askVisionOnImage(fr,
                                OverviewPanel.displayNameOf(a) + "+" + OverviewPanel.displayNameOf(b) + " combined",
                                get(), null, question, null);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(fr, "Could not compose combined image:\n" + ex.getMessage(),
                                "Ask Vision failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        } else if (1 == choice) {
            askVisionSequentially(fr, List.of(a, b), 0, question);
        }
    }

    /**
     * Fires "Selected &gt; Ask Vision…" for {@code entries.get(index)}, then
     * (via {@link RegionView#askVision}'s {@code onComplete} callback) moves
     * to the next entry once that one's answer dialog has been dismissed —
     * one call at a time, never several concurrently, so predator's vision
     * pipeline only ever sees one request at once and the shared
     * {@link BusyIndicator} on/off state stays meaningful.
     */
    private static void askVisionSequentially(JFrame fr, List<CatalogEntry> entries, int index, String question) {
        if (index >= entries.size()) {
            return;
        }
        CatalogEntry entry = entries.get(index);
        RegionView.askVision(fr, entry, entry.mainRegion(), question, new Runnable() {
            @Override
            public void run() {
                askVisionSequentially(fr, entries, index + 1, question);
            }
        });
    }

    /**
     * Crops {@code full} to {@code entry}'s {@link CatalogEntry#mainRegion()}
     * bounding box (same {@link BitSet2D#cropToPolygon} path
     * {@code CatalogCli extract --content-area} uses), or returns
     * {@code full} unchanged if no content area has been traced yet.
     */
    private static BufferedImage cropToContentArea(BufferedImage full, CatalogEntry entry) {
        CatalogEntry.Region main = entry.mainRegion();
        if (null == main) {
            return full;
        }
        List<java.awt.Point> vertices = new ArrayList<>(main.polygon.size());
        for (CatalogEntry.Vertex v : main.polygon) {
            vertices.add(new java.awt.Point(v.x, v.y));
        }
        BufferedImage cropped = BitSet2D.cropToPolygon(full, vertices);
        if (0.0 != main.angle) {
            cropped = BitSet2D.rotateUpright(cropped, main.angle);
        }
        return cropped;
    }

    /**
     * Reads {@code verso} and {@code recto}'s full-resolution files, composes
     * them side by side (verso left, recto right — the order an open book
     * spread reads) via {@link ImageGrid}, and opens the result in infimg —
     * same "compose then hand to infimg for save/discard/clipboard" shape as
     * {@link RegionView#openInInfimg}, not a new in-app viewer. When
     * {@code contentAreaOnly} is set (mirroring {@link OverviewPanel}'s own
     * "Content Area Only" toggle, so the side-by-side view matches whatever
     * the thumbnail grid is already showing), each source is cropped to its
     * {@link CatalogEntry#mainRegion()} bounding box first — a page with no
     * content area traced yet falls back to the full page rather than
     * failing the whole composite.
     */
    private static void openTwoPageView(JFrame fr, CatalogEntry verso, CatalogEntry recto, boolean contentAreaOnly) {
        File versoFile = ImageDisplay.pickExistingFile(verso);
        File rectoFile = ImageDisplay.pickExistingFile(recto);
        if (null == versoFile || null == rectoFile) {
            return;
        }
        RegionView.busy.enter();
        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws IOException {
                BufferedImage a = ImageIO.read(versoFile);
                BufferedImage b = ImageIO.read(rectoFile);
                if (contentAreaOnly) {
                    a = cropToContentArea(a, verso);
                    b = cropToContentArea(b, recto);
                }
                int cellW = Math.max(a.getWidth(), b.getWidth());
                int cellH = Math.max(a.getHeight(), b.getHeight());
                BufferedImage composite = ImageGrid.paint(List.of(a, b), 2, new Dimension(cellW, cellH));
                File target = new File(System.getProperty("java.io.tmpdir"),
                        OverviewPanel.displayNameOf(verso) + "+" + OverviewPanel.displayNameOf(recto)
                                + "." + System.currentTimeMillis() + ".png");
                ImageIO.write(composite, "png", target);
                return target;
            }

            @Override
            protected void done() {
                RegionView.busy.exit();
                try {
                    Voynich.launchImageView(get());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(fr, "Could not open Two-Page View:\n" + ex.getMessage(),
                            "Open failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Composes {@code selected}'s already-decoded 256×256 thumbnails
     * ({@link OverviewPanel#thumbnailOf}) into one square-ish grid image via
     * {@link ImageGrid}, and opens the result in infimg — same
     * compose-then-infimg shape as Two-Page View, so both new grid features
     * share one consistent path. Nags first if the computed grid is bigger
     * than the current screen's usable bounds ({@link ViewFrame}'s own
     * insets-aware size check, reused rather than duplicated).
     */
    private static void openThumbnailMatrix(JFrame fr, OverviewPanel overview, List<CatalogEntry> selected) {
        if (selected.isEmpty()) {
            return;
        }
        Dimension cellSize = new Dimension(ColorImage.THUMB_SIZE, ColorImage.THUMB_SIZE);
        int columns = ImageGrid.squareColumns(selected.size());
        Dimension gridSize = ImageGrid.dimensions(selected.size(), columns, cellSize);
        Rectangle usable = ViewFrame.usableBounds(ViewFrame.defaultDevice(fr));
        if (gridSize.width > usable.width || gridSize.height > usable.height) {
            int choice = JOptionPane.showConfirmDialog(fr,
                    "This " + columns + "x" + ((int) Math.ceil((double) selected.size() / columns))
                    + " matrix (" + gridSize.width + "x" + gridSize.height + " px) is larger than your screen ("
                    + usable.width + "x" + usable.height + "); continue?",
                    "Matrix larger than screen", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (JOptionPane.YES_OPTION != choice) {
                return;
            }
        }
        List<BufferedImage> thumbs = new ArrayList<>();
        for (CatalogEntry entry : selected) {
            thumbs.add(overview.thumbnailOf(entry));
        }
        RegionView.busy.enter();
        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws IOException {
                BufferedImage composite = ImageGrid.paint(thumbs, columns, cellSize);
                File target = new File(System.getProperty("java.io.tmpdir"),
                        "matrix." + System.currentTimeMillis() + ".png");
                ImageIO.write(composite, "png", target);
                return target;
            }

            @Override
            protected void done() {
                RegionView.busy.exit();
                try {
                    Voynich.launchImageView(get());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(fr, "Could not open Thumbnail Matrix:\n" + ex.getMessage(),
                            "Open failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
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
