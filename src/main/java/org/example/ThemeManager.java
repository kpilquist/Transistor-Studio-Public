package org.example;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.UIResource;
import java.awt.*;
import java.util.prefs.Preferences;

/**
 * Centralized theme manager for the Swing UI.
 * Supports saving/restoring the selected theme and applying color palettes
 * over the installed Look & Feel (FlatDarkLaf by default).
 */
public final class ThemeManager {

    public enum Theme {
        SYSTEM, // base FlatDarkLaf colors
        RED,
        BLUE,
        BLACK
    }

    private static final String PREF_NODE_CLASS = Main.class.getName();
    private static final String PREF_KEY_THEME = "ui.theme";

    private ThemeManager() {}

    /** Install Look & Feel and apply saved theme colors. */
    public static void setupLookAndFeelAndTheme() {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception e) {
            System.err.println("ThemeManager: Failed to set FlatDarkLaf: " + e.getMessage());
        }
        Theme saved = getSavedTheme();
        applyTheme(saved);
    }

    public static Theme getSavedTheme() {
        Preferences prefs = Preferences.userRoot().node(PREF_NODE_CLASS);
        String val = prefs.get(PREF_KEY_THEME, Theme.SYSTEM.name());
        try {
            return Theme.valueOf(val);
        } catch (IllegalArgumentException ex) {
            return Theme.SYSTEM;
        }
    }

    public static void saveTheme(Theme theme) {
        Preferences prefs = Preferences.userRoot().node(PREF_NODE_CLASS);
        prefs.put(PREF_KEY_THEME, theme.name());
    }

    /**
     * Apply theme colors across UIManager, then update all open windows.
     */
    public static void applyTheme(Theme theme) {
        // Reset to LAF defaults first by reloading defaults of current LAF
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        for (Object key : defaults.keySet()) {
            UIManager.put(key, defaults.get(key));
        }

        switch (theme) {
            case SYSTEM:
                // Keep FlatDarkLaf defaults
                break;
            case RED:
                applyPalette(new Color(0x22, 0x22, 0x22), // background
                             new Color(0xFF, 0x55, 0x55), // accent
                             new Color(0xEE, 0xEE, 0xEE)); // foreground
                break;
            case BLUE:
                applyPalette(new Color(0x1E, 0x25, 0x2F),
                             new Color(0x4C, 0xAF, 0xF0),
                             new Color(0xE6, 0xF1, 0xFF));
                break;
            case BLACK:
                applyPalette(Color.BLACK,
                             new Color(0x33, 0x99, 0x33),
                             new Color(0xDD, 0xDD, 0xDD));
                break;
        }

        saveTheme(theme);
        refreshAllWindows();
    }

    private static void applyPalette(Color background, Color accent, Color foreground) {
        // Wrap as UIResources so LAF can override them on future changes
        ColorUIResource bg = new ColorUIResource(background);
        ColorUIResource fg = new ColorUIResource(foreground);
        ColorUIResource acc = new ColorUIResource(accent);
        ColorUIResource accDark = new ColorUIResource(new Color(
                Math.max(accent.getRed() - 40, 0),
                Math.max(accent.getGreen() - 40, 0),
                Math.max(accent.getBlue() - 40, 0)));

        // Common component backgrounds
        UIManager.put("Panel.background", bg);
        UIManager.put("Viewport.background", bg);
        UIManager.put("SplitPane.background", bg);
        UIManager.put("ToolBar.background", bg);
        UIManager.put("MenuBar.background", bg);
        UIManager.put("Menu.background", bg);
        UIManager.put("MenuItem.background", bg);
        UIManager.put("TextArea.background", bg);
        UIManager.put("TextField.background", new ColorUIResource(background.darker()));
        UIManager.put("ScrollPane.background", bg);

        // Foregrounds
        UIManager.put("Label.foreground", fg);
        UIManager.put("Menu.foreground", fg);
        UIManager.put("MenuItem.foreground", fg);
        UIManager.put("Button.foreground", fg);
        UIManager.put("ToggleButton.foreground", fg);
        UIManager.put("TextArea.foreground", fg);
        UIManager.put("TextField.foreground", fg);

        // Prefer FlatLaf accent key rather than overriding button backgrounds
        // This lets FlatLaf paint states correctly and keeps buttons theme-aware
        UIManager.put("Component.accentColor", acc);
        UIManager.put("Component.focusColor", acc);

        // Selection and other accents
        UIManager.put("TextComponent.selectionBackground", accDark);
        UIManager.put("TextComponent.selectionForeground", new ColorUIResource(Color.BLACK));
        UIManager.put("TabbedPane.selected", acc);
        UIManager.put("TabbedPane.focus", acc);
        UIManager.put("ComboBox.selectionBackground", acc);
        UIManager.put("ComboBox.selectionForeground", new ColorUIResource(Color.BLACK));
        UIManager.put("CheckBox.icon.checkmarkColor", acc);
        UIManager.put("ScrollBar.thumb", accDark);
        // Do NOT set "Button.background" or "ToggleButton.background" to avoid sticky colors
    }

    private static void refreshAllWindows() {
        for (Window w : Window.getWindows()) {
            // Normalize specific components that may have been manually colored
            normalizeComponentHierarchy(w);

            SwingUtilities.updateComponentTreeUI(w);

            // Touch menu bar to ensure update and then revalidate/repaint
            if (w instanceof JFrame) {
                ((JFrame) w).getJMenuBar();
            }
            w.invalidate();
            w.validate();
            w.repaint();
        }
    }

    private static void normalizeComponentHierarchy(Component c) {
        if (c instanceof AbstractButton) {
            AbstractButton b = (AbstractButton) c;
            // If user-set (non-UIResource) colors exist, reset to defaults so theme can apply
            Color bg = b.getBackground();
            if (bg != null && !(bg instanceof UIResource)) {
                b.setBackground(null);
            }
            Color fg = b.getForeground();
            if (fg != null && !(fg instanceof UIResource)) {
                b.setForeground(null);
            }
        }
        if (c instanceof JMenu) {
            JMenu menu = (JMenu) c;
            for (Component mc : menu.getMenuComponents()) {
                normalizeComponentHierarchy(mc);
            }
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                normalizeComponentHierarchy(child);
            }
        }
    }
}
