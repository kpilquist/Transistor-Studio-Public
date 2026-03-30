package org.example;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class SplashScreen {
    // Executor service for cleanup
    private static ScheduledExecutorService timeoutExecutor;

    public static void main(String[] args) {
        // Add shutdown hook to ensure proper cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("SplashScreen: Shutdown hook triggered, cleaning up resources...");
            if (timeoutExecutor != null && !timeoutExecutor.isShutdown()) {
                timeoutExecutor.shutdownNow();
                System.out.println("SplashScreen: Timeout executor shut down from shutdown hook.");
            }
            System.out.println("SplashScreen: All resources cleaned up.");
        }));

        // Load the icon image
        ImageIcon icon = new ImageIcon("resources/icons/ts.png");
        // Load the splash screen image
        ImageIcon splashImage = new ImageIcon("resources/images/splash.png");

        int width = splashImage.getIconWidth();
        int height = splashImage.getIconHeight();

        // Create a new JFrame
        JFrame frame = new JFrame();
        frame.setUndecorated(true); // Remove window decorations
        frame.setSize(width, height);
        frame.setLocationRelativeTo(null); // Center the frame on the screen
        frame.setIconImage(icon.getImage()); // Set the application icon
        frame.setAlwaysOnTop(true); // Keep the splash screen always on top

        // Create a JPanel to hold the splash screen content
        JPanel panel = new JPanel();
        panel.setLayout(new OverlayLayout(panel));

        // Create a JLabel for the splash screen image
        JLabel label = new JLabel(splashImage);
        panel.add(label);

        // Overlay panel for the bottom-right Reset button
        JPanel overlay = new JPanel(null);
        overlay.setOpaque(false);
        JButton resetBtn = new JButton("Reset Settings");
        // Slightly widen the button so text isn't clipped on some DPIs/fonts
        Dimension btnSize = resetBtn.getPreferredSize();
        int extraW = 16;
        Dimension widened = new Dimension(btnSize.width + extraW, btnSize.height);
        resetBtn.setPreferredSize(widened);
        resetBtn.setSize(widened);
        // Place the button near the bottom-right corner with 12px margin
        resetBtn.setBounds(width - widened.width - 12, height - widened.height - 12, widened.width, widened.height);
        overlay.add(resetBtn);
        // Ensure overlay takes full splash size for absolute positioning
        overlay.setPreferredSize(new Dimension(width, height));
        panel.add(overlay);
        // Ensure the overlay stays above the image even after LAF/theme updates
        panel.setComponentZOrder(overlay, 0);
        panel.setComponentZOrder(label, 1);
        panel.revalidate();
        panel.repaint();

        // Wire the reset action
        resetBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(frame,
                    "This will reset all settings to defaults. Continue?",
                    "Reset Settings",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.OK_OPTION) {
                try {
                    // Clear the shared package preferences used across modules (entire subtree)
                    Preferences userRoot = Preferences.userRoot();
                    if (userRoot.nodeExists("/org/example")) {
                        userRoot.node("/org/example").removeNode();
                    }

                    // Clear ThemeManager's separate node (uses full class name as a node name under root)
                    String themeNodePath = Main.class.getName(); // e.g. "org.example.Main"
                    if (userRoot.nodeExists(themeNodePath)) {
                        userRoot.node(themeNodePath).removeNode();
                    }

                    // Best-effort sync to persist removals
                    userRoot.flush();

                    JOptionPane.showMessageDialog(frame,
                            "Settings were reset. The app will start with defaults (maximized).",
                            "Reset Complete",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (BackingStoreException ex) {
                    LogBuffer.error("SplashScreen: Failed to reset settings", ex);
                    JOptionPane.showMessageDialog(frame,
                            "Failed to reset settings: " + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        // Add the panel to the frame
        frame.add(panel);

        // Set the frame to be visible
        frame.setVisible(true);
        System.out.println("SplashScreen: Splash screen displayed.");
        final long splashShownAt = System.currentTimeMillis();

        // Use a CountDownLatch to wait for the Main class to load
        CountDownLatch latch = new CountDownLatch(1);
        System.out.println("SplashScreen: Starting Main class in separate thread...");

        // Start the Main class in a separate thread
        Thread mainThread = new Thread(() -> {
            try {
                System.out.println("SplashScreen: Calling Main.main()...");
                // Pass an empty list of serial ports since DeviceInitializer is gone
                Main.main(new String[]{}, java.util.Collections.emptyList());
                System.out.println("SplashScreen: Main.main() returned, waiting for UI initialization...");

                // Wait for the UI to be fully initialized
                try {
                    Main.uiInitializedLatch.await();
                    System.out.println("SplashScreen: UI fully initialized");
                } catch (InterruptedException e) {
                    System.out.println("SplashScreen: Interrupted while waiting for UI initialization");
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                System.out.println("SplashScreen: Exception in Main thread: " + e.getMessage());
                LogBuffer.error("SplashScreen: Exception in Main thread", e);
            } finally {
                System.out.println("SplashScreen: Counting down latch to signal Main class has finished loading");
                latch.countDown(); // Signal that the Main class has finished loading
            }
        });
        // Mark as daemon thread so it doesn't prevent JVM from exiting
        mainThread.setDaemon(true);
        mainThread.start();

        // Use a ScheduledExecutorService to enforce a timeout
        System.out.println("SplashScreen: Setting up timeout for UI initialization...");
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        timeoutExecutor.schedule(() -> {
            System.out.println("SplashScreen: Checking if UI initialization is complete...");
            if (latch.getCount() > 0) {
                System.out.println("SplashScreen: Timeout reached. Main UI initialization is taking too long.");
                frame.dispose(); // Close the splash screen if Main hasn't loaded
            } else {
                System.out.println("SplashScreen: UI initialization completed before timeout.");
            }
            timeoutExecutor.shutdown(); // Clean up the executor
            System.out.println("SplashScreen: Timeout executor shut down.");
        }, 15, TimeUnit.SECONDS); // Timeout after 15 seconds (increased from 5)

        // Wait for the Main class to load or timeout
        System.out.println("SplashScreen: Starting thread to wait for Main class to finish loading...");
        Thread waitThread = new Thread(() -> {
            try {
                System.out.println("SplashScreen: Waiting for Main class to finish loading...");
                latch.await(); // Wait for the Main class to finish loading
                long elapsed = System.currentTimeMillis() - splashShownAt;
                long minMillis = 5000L; // ensure at least 5 seconds
                long remaining = minMillis - elapsed;
                if (remaining > 0) {
                    System.out.println("SplashScreen: Main ready early. Holding splash for " + remaining + " ms to meet minimum display time...");
                    try {
                        Thread.sleep(remaining);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                System.out.println("SplashScreen: Minimum display time met. Disposing splash screen...");
                SwingUtilities.invokeLater(() -> {
                    frame.dispose(); // Close the splash screen
                    System.out.println("SplashScreen: Splash screen disposed.");
                });
            } catch (InterruptedException e) {
                System.out.println("SplashScreen: Interrupted while waiting for Main class to finish loading.");
                Thread.currentThread().interrupt();
            }
        });
        // Mark as daemon thread so it doesn't prevent JVM from exiting
        waitThread.setDaemon(true);
        waitThread.start();
    }
}
