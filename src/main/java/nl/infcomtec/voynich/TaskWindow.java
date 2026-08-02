/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/**
 * A window that runs one background task and shows its progress. This is the
 * app-wide pattern for anything that takes long enough to need a progress
 * indicator: start the task, open a {@code TaskWindow} showing what it's
 * doing, let the user watch it or iconify it and move on.
 * <p>
 * While the task is running, closing the window (the X button) iconifies it
 * rather than disposing it — the task keeps running headless either way, and
 * the only way to actually stop it is the Cancel button. This is deliberate:
 * an accidental window-close shouldn't silently kill a long-running scan.
 * Once the task has finished (or been cancelled), both the X button and the
 * same button — now relabeled Close — dispose the window instead, since
 * there's nothing left to protect.
 * </p>
 * <p>
 * {@link #getOrNull(String)} plus one window instance per concrete subclass
 * gives "one window per task-type": a task type already showing its window
 * gets refocused instead of spawning a second, redundant progress window.
 * Calling {@link #start()} again on that same window re-runs the task if the
 * previous run has finished, or just brings the window forward if one is
 * still in progress — see {@link #start()}.
 * </p>
 */
public abstract class TaskWindow extends JFrame {

    private static final Map<String, TaskWindow> OPEN_WINDOWS = new HashMap<>();

    private final String taskType;
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea log = new JTextArea(16, 60);
    private final EzAction cancelAction;
    private Worker worker;

    /**
     * @param taskType registry key; only one window per key exists at a time
     * @param title window title
     * @param owner window to position relative to, or {@code null} to use
     * the platform default placement
     */
    protected TaskWindow(String taskType, String title, Window owner) {
        this.taskType = taskType;
        setTitle(title);
        setLayout(new BorderLayout());

        log.setEditable(false);
        add(new JScrollPane(log), BorderLayout.CENTER);
        add(progressBar, BorderLayout.NORTH);

        cancelAction = new EzAction("Cancel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isRunning()) {
                    worker.cancel(true);
                } else {
                    dispose();
                }
            }
        };
        JPanel south = new JPanel();
        south.add(new JButton(cancelAction));
        add(south, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);

        this.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (isRunning()) {
                    setExtendedState(Frame.ICONIFIED);
                } else {
                    dispose();
                }
            }
        });

        synchronized (OPEN_WINDOWS) {
            OPEN_WINDOWS.put(taskType, this);
        }
    }

    /**
     * @param taskType the registry key passed to some subclass's constructor
     * @return the already-open window for that task type, restored and
     * brought to front, or {@code null} if no such window has been created
     * yet (the caller should construct one)
     */
    public static TaskWindow getOrNull(String taskType) {
        synchronized (OPEN_WINDOWS) {
            TaskWindow existing = OPEN_WINDOWS.get(taskType);
            if (null != existing) {
                existing.setExtendedState(Frame.NORMAL);
                existing.toFront();
                existing.requestFocus();
            }
            return existing;
        }
    }

    /**
     * The work this window represents. Runs off the EDT; call
     * {@link #publishLine(String)} and {@link #setProgressPercent(int)} to
     * report status, and {@link #isCancelRequested()} to check for a
     * user-requested cancel at any natural break point. Implementations
     * should {@link #publishLine(String)} their own terminal status (done,
     * cancelled, or otherwise) before returning — {@code done()} only logs
     * the case of an exception escaping this method uncaught, since by then
     * there's nothing left to race against in the log.
     *
     * @throws Exception on failure; caught and shown in the log
     */
    protected abstract void runTask() throws Exception;

    /**
     * Appends one line to the log, from any thread.
     *
     * @param line text to append
     */
    protected final void publishLine(String line) {
        worker.publishLine(line);
    }

    /**
     * Updates the progress bar, from any thread.
     *
     * @param percent 0-100
     */
    protected final void setProgressPercent(int percent) {
        worker.setProgressPercent(Math.max(0, Math.min(100, percent)));
    }

    /**
     * @return {@code true} once the user has clicked Cancel; long-running
     * {@link #runTask()} implementations should check this between units of
     * work and return early when it flips
     */
    protected final boolean isCancelRequested() {
        return worker.isCancelled();
    }

    /**
     * @return {@code true} while a run is in progress (used to decide whether
     * closing the window should iconify it or dispose it, and whether the
     * bottom button cancels the run or dismisses the finished window)
     */
    private boolean isRunning() {
        return null != worker && !worker.isDone();
    }

    /**
     * Starts the background task and shows the window. Safe to call on a
     * window returned by {@link #getOrNull(String)}: if its previous run has
     * finished or been cancelled, this starts a fresh one (clearing the log
     * and progress bar first); if a run is still in progress, this just
     * brings the window forward instead of starting a second, concurrent one.
     */
    public void start() {
        if (null != worker && !worker.isDone()) {
            setExtendedState(Frame.NORMAL);
            toFront();
            requestFocus();
            return;
        }
        log.setText("");
        progressBar.setValue(0);
        cancelAction.setName("Cancel");
        worker = new Worker();
        worker.addPropertyChangeListener(new ProgressListener());
        worker.execute();
        setVisible(true);
    }

    private final class ProgressListener implements PropertyChangeListener {
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        }
    }

    private final class Worker extends SwingWorker<Void, String> {

        void publishLine(String line) {
            publish(line);
        }

        void setProgressPercent(int percent) {
            setProgress(percent);
        }

        @Override
        protected Void doInBackground() throws Exception {
            runTask();
            return null;
        }

        @Override
        protected void process(List<String> chunks) {
            for (String line : chunks) {
                log.append(line);
                log.append("\n");
            }
            log.setCaretPosition(log.getDocument().getLength());
        }

        @Override
        protected void done() {
            try {
                get();
            } catch (CancellationException ex) {
                // runTask() is expected to have already reported this via
                // publishLine before returning; nothing more to log here.
            } catch (Exception ex) {
                // Appended directly rather than via publishLine: doInBackground()
                // has already returned by the time done() runs, so there's no
                // more queued output this could race against.
                log.append("Failed: " + ex.getCause() + "\n");
            }
            cancelAction.setName("Close");
        }
    }
}
