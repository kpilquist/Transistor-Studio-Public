package org.example;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.util.Enumeration;
import java.util.prefs.Preferences;

/**
 * Global UI scaling utility. Computes a scale factor based on the user's screen resolution
 * relative to a configurable "design-time" reference resolution and applies it to Swing UI defaults.
 */
public final class UIScaler {

    private UIScaler() {}

    public static final String KEY_AUTO = "ui.scale.auto";
    public static final String KEY_DESIGN_W = "ui.design.width";
    public static final String KEY_DESIGN_H = "ui.design.height";
    public static final String KEY_MANUAL_PERCENT = "ui.scale.manualPercent";

    // Reasonable defaults
    public static final int DEFAULT_DESIGN_W = 1920;
    public static final int DEFAULT_DESIGN_H = 1080;
    public static final int DEFAULT_MANUAL_PERCENT = 100; // 100% = no scaling

    public static final float MIN_SCALE = 0.75f;
    public static final float MAX_SCALE = 2.0f;

    private static volatile float currentScale = 1.0f;

    /** Returns the last applied scale. */
    public static float getCurrentScale() {
        return currentScale;
    }

    /** Scales a pixel value by the current scale, rounding to nearest int. */
    public static int scale(int px) {
        return Math.round(px * currentScale);
    }

    /**
     * Loads preferences, computes the scale factor for the primary screen, clamps it,
     * and applies it to Swing default fonts and some common size hints. Should be called
     * after Look&Feel is set and before building most UI.
     */
    public static float applyGlobalScalingFromPrefs() {
        Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);
        boolean auto = prefs.getBoolean(KEY_AUTO, true);
        int designW = clamp(prefs.getInt(KEY_DESIGN_W, DEFAULT_DESIGN_W), 640, 10000);
        int designH = clamp(prefs.getInt(KEY_DESIGN_H, DEFAULT_DESIGN_H), 480, 10000);
        int manualPercent = clamp(prefs.getInt(KEY_MANUAL_PERCENT, DEFAULT_MANUAL_PERCENT), (int)(MIN_SCALE*100), (int)(MAX_SCALE*100));

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        float scale;
        if (auto) {
            float sx = screen.width / (float) designW;
            float sy = screen.height / (float) designH;
            // Use the smaller to avoid oversizing beyond screen boundaries
            scale = Math.min(sx, sy);
        } else {
            scale = manualPercent / 100f;
        }
        scale = clamp(scale, MIN_SCALE, MAX_SCALE);

        applyScaleToUIDefaults(scale);
        currentScale = scale;
        return scale;
    }

    /** Applies the given scale to UIManager defaults (fonts and some metrics). */
    private static void applyScaleToUIDefaults(float scale) {
        UIDefaults defaults = UIManager.getDefaults();
        // Scale all fonts
        Enumeration<Object> keys = defaults.keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object val = defaults.get(key);
            if (val instanceof FontUIResource) {
                FontUIResource f = (FontUIResource) val;
                int newSize = Math.max(10, Math.round(f.getSize2D() * scale));
                FontUIResource f2 = new FontUIResource(f.deriveFont((float) newSize));
                defaults.put(key, f2);
            }
        }
        // Some common size hints
        UIManager.put("Button.arc", Math.round(getInt(defaults.get("Button.arc"), 8) * scale));
        UIManager.put("Component.arrowType", defaults.get("Component.arrowType")); // leave
        UIManager.put("Component.focusWidth", Math.round(getInt(defaults.get("Component.focusWidth"), 1) * scale));
        UIManager.put("Component.innerFocusWidth", Math.round(getInt(defaults.get("Component.innerFocusWidth"), 1) * scale));
        UIManager.put("Component.arc", Math.round(getInt(defaults.get("Component.arc"), 8) * scale));
        UIManager.put("ScrollBar.width", Math.round(getInt(defaults.get("ScrollBar.width"), 14) * scale));
        UIManager.put("Slider.thumbSize", new Dimension(
                Math.round(getDim(defaults.get("Slider.thumbSize"), new Dimension(16,16)).width * scale),
                Math.round(getDim(defaults.get("Slider.thumbSize"), new Dimension(16,16)).height * scale))
        );
        UIManager.put("Slider.trackWidth", Math.round(getInt(defaults.get("Slider.trackWidth"), 4) * scale));
        UIManager.put("TextComponent.arc", Math.round(getInt(defaults.get("TextComponent.arc"), 8) * scale));
        UIManager.put("ProgressBar.arc", Math.round(getInt(defaults.get("ProgressBar.arc"), 8) * scale));
        UIManager.put("TabbedPane.tabHeight", Math.round(getInt(defaults.get("TabbedPane.tabHeight"), 28) * scale));
    }

    /** Optionally apply to an existing window tree. Not perfect for all layout metrics. */
    public static void applyAndUpdateWindowTree(Window window) {
        applyGlobalScalingFromPrefs();
        if (window != null) {
            SwingUtilities.updateComponentTreeUI(window);
            window.pack();
        }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    private static int getInt(Object o, int def) {
        if (o instanceof Integer) return (Integer) o;
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignore) { return def; }
    }
    private static Dimension getDim(Object o, Dimension def) {
        if (o instanceof Dimension) return (Dimension) o;
        return def;
    }
}
