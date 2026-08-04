/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.formdev.flatlaf.FlatDarculaLaf;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

/**
 * Entry point. Loads config, validates {@code scanPath}, builds the main
 * {@link JFrame}.
 */
public class Voynich {

    public static final String TITLE = "Voynich tools by InfcomTec";
    /**
     * Path to the config file. Defaults to {@code ~/.infVoy.json}, overridable
     * via the first CLI argument.
     */
    public static File configFile = new File(System.getProperty("user.home"), ".infVoy.json");
    /**
     * The config loaded from {@link #configFile} at startup.
     */
    public static Config config;

    /**
     * Main.
     *
     * @param args Only one argument possible, if given the path to the
     * configuration file.
     */
    public static void main(String[] args) {
        FlatDarculaLaf.setup();
        if (args.length > 0) {
            configFile = new File(args[0]);
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
        }));
        toolBar.add(new JButton(new EzAction("Sort") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.sort();
            }
        }));
        toolBar.add(new JButton(new EzAction("Filter") {
            @Override
            public void actionPerformed(ActionEvent e) {
                overview.filter();
            }
        }));
        toolBar.add(new JButton(new EzAction("Exit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        }));
        outer.add(toolBar, BorderLayout.NORTH);
        outer.add(overview, BorderLayout.CENTER);
        fr.setContentPane(outer);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fr.setVisible(true);
            }
        });
    }
}
