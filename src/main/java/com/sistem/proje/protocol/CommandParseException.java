package com.sistem.proje.protocol;

/**
 * Komut parse hatası
 */
public class CommandParseException extends Exception {
    public CommandParseException(String message) {
        super(message);
    }

    public CommandParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

