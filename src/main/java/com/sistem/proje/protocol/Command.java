package com.sistem.proje.protocol;

/**
 * Komut abstract class'ı - Tüm komutlar için ortak yapı
 */
public abstract class Command {
    protected final String id;

    /**
     * Command constructor - ID validasyonu yapar
     */
    protected Command(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("ID boş olamaz");
        }
        this.id = id.trim();
    }

    /**
     * ID'yi döndürür
     */
    public String getId() {
        return id;
    }

    /**
     * Komut tipini döndürür - Alt sınıflar implement eder
     */
    public abstract CommandType getType();
}

