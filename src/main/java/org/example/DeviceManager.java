package org.example;

import com.fazecast.jSerialComm.SerialPort;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.Line;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages hardware device enumeration and connection.
 * Handles Serial Ports (for Radio Control) and Audio Devices (for Signal Processing).
 */
public class DeviceManager {

    /**
     * Gets a list of available Serial Ports (COM ports).
     * @return List of system port names (e.g., "COM3", "/dev/ttyUSB0")
     */
    public static List<String> getSerialPorts() {
        List<String> portNames = new ArrayList<>();
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            for (SerialPort port : ports) {
                portNames.add(port.getSystemPortName());
            }
        } catch (Exception e) {
            System.err.println("Error listing serial ports: " + e.getMessage());
        }
        return portNames;
    }

    /**
     * Gets a list of available Audio Input devices (Microphones, Line Ins).
     * @return List of mixer names
     */
    public static List<String> getAudioInputDevices() {
        List<String> inputDevices = new ArrayList<>();
        // Always offer in-process loopback as a virtual input for local testing
        inputDevices.add(LoopbackBus.LOOPBACK_DEVICE_NAME);

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                // Check if this mixer supports TargetDataLine (Input)
                Line.Info lineInfo = new Line.Info(TargetDataLine.class);
                if (mixer.isLineSupported(lineInfo)) {
                    inputDevices.add(mixerInfo.getName());
                }
            } catch (Exception e) {
                // Ignore inaccessible mixers
            }
        }
        return inputDevices;
    }

    /**
     * Gets a list of available Audio Output devices (Speakers, Line Outs).
     * @return List of mixer names
     */
    public static List<String> getAudioOutputDevices() {
        List<String> outputDevices = new ArrayList<>();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                // Check if this mixer supports SourceDataLine (Output)
                Line.Info lineInfo = new Line.Info(SourceDataLine.class);
                if (mixer.isLineSupported(lineInfo)) {
                    outputDevices.add(mixerInfo.getName());
                }
            } catch (Exception e) {
                // Ignore inaccessible mixers
            }
        }
        return outputDevices;
    }
}

