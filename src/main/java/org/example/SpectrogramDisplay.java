package org.example;

import javax.swing.JComponent;
import java.util.List;

/**
 * Common interface for spectrogram displays (CPU and GPU).
 */
public interface SpectrogramDisplay {
    /**
     * Updates the spectrogram with new frequency magnitude data.
     * @param magnitude Array of magnitude values.
     */
    void updateSpectrogram(double[] magnitude);

    /**
     * Updates the spectrogram with new frequency magnitude data (compatibility mode).
     * @param data 2D array where data[i][0] is the magnitude.
     */
    void updateSpectrogram(double[][] data);

    /**
     * Sets the start frequency for the ruler.
     * @param startFrequency Frequency in Hz.
     */
    void setStartFrequency(long startFrequency);

    /**
     * Sets the bandwidth (frequency range) displayed.
     * @param frequencies Bandwidth in Hz.
     */
    void setFrequencies(int frequencies);

    /**
     * Sets the input sample rate of the audio source.
     * This is used to calculate the frequency of each bin in the magnitude data.
     * @param sampleRate Sample rate in Hz.
     */
    void setInputSampleRate(int sampleRate);

    /**
     * Cleans up resources.
     */
    void cleanup();

    /**
     * Returns the Swing component to be added to the UI.
     * @return The JComponent (JPanel or JFXPanel).
     */
    JComponent getComponent();

    /**
     * Sets the color theme for the waterfall.
     * @param theme Theme name (e.g., "Spectrum", "Fire", "Grayscale", "Ocean").
     */
    void setColorTheme(String theme);

    /**
     * Sets the scroll speed (or update rate) of the waterfall.
     * @param speed Speed identifier (e.g., 0=Slow, 1=Normal, 2=Fast).
     */
    void setScrollSpeed(int speed);

    /**
     * Adds a selection to the spectrogram.
     * @param selection The selection to add.
     */
    void addSelection(SpectrogramSelection selection);

    /**
     * Removes a selection from the spectrogram.
     * @param selection The selection to remove.
     */
    void removeSelection(SpectrogramSelection selection);

    /**
     * Clears all selections.
     */
    void clearSelections();

    /**
     * Gets all current selections.
     * @return List of selections.
     */
    List<SpectrogramSelection> getSelections();

    /**
     * Returns the current active selection/marker, or null if none.
     */
    SpectrogramSelection getActiveSelection();

    /**
     * Sets a listener to be notified when the active selection/marker changes (e.g., user clicks a marker).
     * @param listener Consumer receiving the active selection, or null if none.
     */
    void setActiveSelectionChangedListener(java.util.function.Consumer<SpectrogramSelection> listener);

    /**
     * Sets the bandwidth for the hover selection cursor.
     * @param bandwidth Bandwidth in Hz.
     */
    void setHoverBandwidth(double bandwidth);

    /**
     * Enables or disables the hover selection cursor.
     * @param enabled True to enable.
     */
    void setHoverEnabled(boolean enabled);

    /**
     * Sets a listener for when a frequency is selected (clicked).
     * @param listener Consumer that accepts the selected frequency in Hz.
     */
    void setSelectionListener(java.util.function.Consumer<Double> listener);

    /**
     * Sets a listener for when a selection is added.
     * @param listener Consumer that accepts the added selection.
     */
    void setSelectionAddedListener(java.util.function.Consumer<SpectrogramSelection> listener);

    /**
     * Sets a listener for when a selection is removed.
     * @param listener Consumer that accepts the removed selection.
     */
    void setSelectionRemovedListener(java.util.function.Consumer<SpectrogramSelection> listener);
    
    /**
     * Sets a listener for when a selection is changed (moved/resized).
     * @param listener Consumer that accepts the changed selection.
     */
    void setSelectionChangedListener(java.util.function.Consumer<SpectrogramSelection> listener);
    
    /**
     * Interaction modes for the spectrogram.
     */
    enum Mode {
        SELECTION,
        ZOOM
    }

    /**
     * Sets the current interaction mode.
     * @param mode The mode to set.
     */
    void setMode(Mode mode);

    /**
     * Gets the current interaction mode.
     * @return The current mode.
     */
    Mode getMode();

    /**
     * Enables or disables placing digital markers (selections) via mouse.
     * When disabled, user clicks will not create new selections.
     * @param enabled true to allow placement
     */
    void setDigitalPlacementEnabled(boolean enabled);

    /**
     * Returns whether placing digital markers is enabled.
     * @return true if enabled
     */
    boolean isDigitalPlacementEnabled();

    /**
     * Sets the visible frequency range (Zoom).
     * @param startFreq Start frequency in Hz.
     * @param endFreq End frequency in Hz.
     */
    void setViewRange(double startFreq, double endFreq);

    /**
     * Resets the view to the full bandwidth.
     */
    void resetView();

    /**
     * Adds a listener for view changes (Zoom).
     * @param listener Consumer that accepts an array [startFreq, endFreq].
     */
    void addViewChangeListener(java.util.function.Consumer<double[]> listener);

    /**
     * Flashes the selection with the given ID to identify it.
     * @param id The ID of the selection to flash.
     */
    void flashSelection(String id);

    /**
     * Sets the radar scan range to visualize on the waterfall.
     * @param startHz Start frequency in Hz.
     * @param endHz End frequency in Hz.
     */
    void setRadarScanRange(double startHz, double endHz);

    /**
     * Returns the frequency at the current mouse position (or last known position).
     * @return Frequency in Hz, or -1 if unknown.
     */
    double getClickFrequency();
}
