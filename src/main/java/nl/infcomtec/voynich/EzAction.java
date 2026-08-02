package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JComponent;

/**
 * Lightweight, source-owned Action with optional styling hints.
 *
 * @author walter
 */
public abstract class EzAction extends AbstractAction {

    public Color background;
    public Color foreground;
    public Font font;

    public EzAction(String name) {
        super(name);
    }

    public EzAction(String name, Icon icon) {
        super(name, icon);
    }

    /* ---- sane accessors ---- */
    public String getName() {
        Object v = getValue(NAME);
        return v != null ? v.toString() : null;
    }

    public Icon getIcon() {
        return (Icon) getValue(SMALL_ICON);
    }

    public void setName(String name) {
        putValue(NAME, name);
    }

    public void setIcon(Icon icon) {
        putValue(SMALL_ICON, icon);
    }

    /* ---- fluent style hints ---- */
    public EzAction withBackColor(Color color) {
        this.background = color;
        return this;
    }

    public EzAction withForeColor(Color color) {
        this.foreground = color;
        return this;
    }

    public EzAction withFont(Font font) {
        this.font = font;
        return this;
    }

    /**
     * Apply styling hints to a component using this action. Must be called
     * explicitly by the creator.
     *
     * @param c Component to style.
     */
    public void applyTo(JComponent c) {
        if (background != null) {
            c.setBackground(background);
        }
        if (foreground != null) {
            c.setForeground(foreground);
        }
        if (font != null) {
            c.setFont(font);
        }
    }

    @Override
    public abstract void actionPerformed(ActionEvent e);
}
