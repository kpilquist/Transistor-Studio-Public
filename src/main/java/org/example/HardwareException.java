package org.example;

/**
 * Thrown when a hardware safety interlock (e.g., SWR monitor) inhibits TX to protect equipment.
 */
public class HardwareException extends RuntimeException {
    public HardwareException(String message) { super(message); }
    public HardwareException(String message, Throwable cause) { super(message, cause); }
}
