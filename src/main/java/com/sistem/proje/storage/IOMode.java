package com.sistem.proje.storage;

/**
 * IO modları enum'u
 */
public enum IOMode {
    /**
     * Buffered IO - BufferedWriter / BufferedReader kullanır
     * Daha yüksek performans için buffer kullanır
     */
    BUFFERED,
    
    /**
     * Unbuffered IO - FileOutputStream / FileInputStream kullanır
     * Direkt dosya IO, buffer kullanmaz
     */
    UNBUFFERED
}

