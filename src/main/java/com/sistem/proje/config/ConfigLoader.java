package com.sistem.proje.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Konfigürasyon dosyası yükleyici
 * tolerance.conf dosyasını okur ve TOLERANCE değerini parse eder
 * TOLERANCE değeri 1 ile 7 arasında olmalıdır
 */
public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String DEFAULT_CONFIG_FILE = "tolerance.conf";
    private static final int MIN_TOLERANCE = 1;
    private static final int MAX_TOLERANCE = 7;
    private static final int DEFAULT_TOLERANCE = 1;
    
    private final Path configFile;
    private Integer tolerance;

    /**
     * Varsayılan tolerance.conf dosyasını kullanır
     */
    public ConfigLoader() {
        this(DEFAULT_CONFIG_FILE);
    }

    /**
     * Belirtilen konfigürasyon dosyasını kullanır
     * 
     * @param configFileName Konfigürasyon dosya adı
     */
    public ConfigLoader(String configFileName) {
        this.configFile = Paths.get(configFileName);
        this.tolerance = null;
    }

    /**
     * Konfigürasyon dosyasını yükler ve parse eder
     * Dosya yoksa default tolerance değerini kullanır
     * 
     * @throws IOException Dosya okuma hatası
     * @throws ConfigException Konfigürasyon parse hatası
     */
    public void load() throws IOException, ConfigException {
        if (!Files.exists(configFile)) {
            // Dosya yoksa default değeri kullan
            this.tolerance = DEFAULT_TOLERANCE;
            String warningMsg = String.format("tolerance.conf not found, using default TOLERANCE=%d", DEFAULT_TOLERANCE);
            logger.warn(warningMsg);
            System.out.println(warningMsg);
            return;
        }

        logger.info("Konfigürasyon dosyası yükleniyor: {}", configFile.toAbsolutePath());

        try (BufferedReader reader = Files.newBufferedReader(configFile)) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Boş satırları ve yorumları atla
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // TOLERANCE değerini parse et
                if (line.startsWith("TOLERANCE=")) {
                    parseTolerance(line, lineNumber);
                } else {
                    logger.warn("Bilinmeyen konfigürasyon satırı (satır {}): {}", lineNumber, line);
                }
            }

            // TOLERANCE değeri bulunamadıysa default değeri kullan
            if (tolerance == null) {
                this.tolerance = DEFAULT_TOLERANCE;
                logger.warn("TOLERANCE değeri konfigürasyon dosyasında bulunamadı, default değer kullanılıyor: {}", DEFAULT_TOLERANCE);
            } else {
                logger.info("Konfigürasyon yüklendi. TOLERANCE: {}", tolerance);
            }
        }
    }

    /**
     * TOLERANCE satırını parse eder
     * 
     * @param line Satır içeriği
     * @param lineNumber Satır numarası (hata mesajları için)
     * @throws ConfigException Parse hatası
     */
    private void parseTolerance(String line, int lineNumber) throws ConfigException {
        try {
            String value = line.substring("TOLERANCE=".length()).trim();
            
            if (value.isEmpty()) {
                throw new ConfigException("TOLERANCE değeri boş (satır " + lineNumber + ")");
            }

            tolerance = Integer.parseInt(value);

            // 1-7 arası validasyon
            if (tolerance < MIN_TOLERANCE || tolerance > MAX_TOLERANCE) {
                throw new ConfigException(
                    String.format("TOLERANCE değeri %d ile %d arasında olmalıdır. Geçersiz değer: %d (satır %d)",
                        MIN_TOLERANCE, MAX_TOLERANCE, tolerance, lineNumber));
            }

            logger.debug("TOLERANCE parse edildi: {} (geçerli aralık: {}-{})", tolerance, MIN_TOLERANCE, MAX_TOLERANCE);

        } catch (NumberFormatException e) {
            throw new ConfigException("TOLERANCE değeri geçersiz format (satır " + lineNumber + "): " + line, e);
        }
    }

    /**
     * TOLERANCE değerini döndürür
     * 
     * @return TOLERANCE değeri (default: 1)
     * @throws ConfigException Konfigürasyon yüklenmemişse (artık olmamalı çünkü default değer var)
     */
    public int getTolerance() throws ConfigException {
        if (tolerance == null) {
            // Bu durum teorik olarak olmamalı ama yine de kontrol ediyoruz
            tolerance = DEFAULT_TOLERANCE;
            logger.warn("Tolerance değeri null, default değer kullanılıyor: {}", DEFAULT_TOLERANCE);
        }
        return tolerance;
    }

    /**
     * Default tolerance değerini döndürür
     * 
     * @return Default TOLERANCE değeri
     */
    public static int getDefaultTolerance() {
        return DEFAULT_TOLERANCE;
    }

    /**
     * Konfigürasyon dosyasının path'ini döndürür
     */
    public Path getConfigFile() {
        return configFile;
    }

    /**
     * Konfigürasyon yüklenmiş mi kontrol eder
     */
    public boolean isLoaded() {
        return tolerance != null;
    }
}

