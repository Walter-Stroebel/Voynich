/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.formdev.flatlaf.FlatDarculaLaf;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FilenameFilter;
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
    private static File[] files;

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

        try {
            files = new File(config.scanPath).listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".png");
                }
            });
            System.out.format("Found %d PNG files.", files.length);
        } catch (Exception any) {
            System.err.println("That did not work: " + any.getMessage());
            System.exit(2);
        }
        final JFrame fr = new JFrame(TITLE);
        fr.setExtendedState(JFrame.MAXIMIZED_BOTH);
        fr.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel outer = new JPanel(new BorderLayout());
        JToolBar toolBar = new JToolBar();
        toolBar.add(new JButton(new EzAction("Exit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        }));
        outer.add(toolBar, BorderLayout.NORTH);
        fr.setContentPane(outer);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                fr.setVisible(true);
            }
        });
    }
}
