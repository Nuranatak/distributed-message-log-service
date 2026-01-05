package com.sistem.proje.config;

/**
 * Konfigürasyon yükleme hatası
 */
public class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

