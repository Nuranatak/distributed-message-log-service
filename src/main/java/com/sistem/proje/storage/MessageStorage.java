package com.sistem.proje.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Disk tabanlı mesaj saklama sınıfı
 * Her mesajı messages/ klasörü altında ayrı dosyada saklar
 * Buffered veya Unbuffered IO modu seçilebilir
 */
public class MessageStorage {
    private static final Logger logger = LoggerFactory.getLogger(MessageStorage.class);
    private static final String MESSAGES_DIR = "messages";
    private static final String FILE_EXTENSION = ".msg";
    private static final IOMode DEFAULT_IO_MODE = IOMode.UNBUFFERED;
    
    private final Path messagesDirectory;
    private final IOMode ioMode;

    /**
     * Varsayılan Buffered IO modu ile oluşturur
     */
    public MessageStorage() {
        this(DEFAULT_IO_MODE);
    }

    /**
     * Belirtilen IO modu ile oluşturur
     * 
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     */
    public MessageStorage(IOMode ioMode) {
        this.messagesDirectory = Paths.get(MESSAGES_DIR);
        this.ioMode = ioMode != null ? ioMode : DEFAULT_IO_MODE;
        initializeDirectory();
        logger.info("MessageStorage başlatıldı. IO Modu: {}", this.ioMode);
    }

    /**
     * Messages klasörünü oluşturur (yoksa)
     */
    private void initializeDirectory() {
        try {
            if (!Files.exists(messagesDirectory)) {
                Files.createDirectories(messagesDirectory);
                logger.info("Messages klasörü oluşturuldu: {}", messagesDirectory.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Messages klasörü oluşturulamadı: ", e);
            throw new RuntimeException("Storage başlatılamadı", e);
        }
    }

    /**
     * Mesajı dosyaya kaydeder (SET işlemi)
     * Dosya varsa overwrite eder, yoksa oluşturur
     * 
     * @param id Mesaj ID'si
     * @param message Kaydedilecek mesaj
     * @throws IOException Dosya yazma hatası
     */
    public void saveMessage(Integer id, String message) throws IOException {
        if (id == null) {
            throw new IllegalArgumentException("ID null olamaz");
        }
        if (message == null) {
            throw new IllegalArgumentException("Message null olamaz");
        }

        Path messageFile = getMessageFilePath(id);
        
        switch (ioMode) {
            case BUFFERED:
                saveMessageBuffered(messageFile, message);
                break;
            case UNBUFFERED:
                saveMessageUnbuffered(messageFile, message);
                break;
        }
        
        logger.debug("Mesaj kaydedildi: id={}, dosya={}, mod={}", id, messageFile, ioMode);
    }

    /**
     * Buffered IO ile mesaj kaydeder
     */
    private void saveMessageBuffered(Path messageFile, String message) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(messageFile, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE)) {
            writer.write(message);
            writer.flush();
        }
    }

    /**
     * Unbuffered IO ile mesaj kaydeder
     */
    private void saveMessageUnbuffered(Path messageFile, String message) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(messageFile.toFile(), false)) {
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            fos.write(messageBytes);
            fos.flush();
        }
    }

    /**
     * Mesajı dosyadan okur (GET işlemi)
     * 
     * @param id Mesaj ID'si
     * @return Mesaj içeriği, dosya yoksa null
     * @throws IOException Dosya okuma hatası
     */
    public String getMessage(Integer id) throws IOException {
        if (id == null) {
            throw new IllegalArgumentException("ID null olamaz");
        }

        Path messageFile = getMessageFilePath(id);
        
        // Dosya yoksa null döndür
        if (!Files.exists(messageFile)) {
            logger.debug("Mesaj bulunamadı: id={}, dosya={}", id, messageFile);
            return null;
        }

        String message;
        switch (ioMode) {
            case BUFFERED:
                message = getMessageBuffered(messageFile);
                break;
            case UNBUFFERED:
                message = getMessageUnbuffered(messageFile);
                break;
            default:
                throw new IllegalStateException("Bilinmeyen IO modu: " + ioMode);
        }
        
        logger.debug("Mesaj okundu: id={}, dosya={}, mod={}", id, messageFile, ioMode);
        return message;
    }

    /**
     * Buffered IO ile mesaj okur
     */
    private String getMessageBuffered(Path messageFile) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(messageFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (content.length() > 0) {
                    content.append(System.lineSeparator());
                }
                content.append(line);
            }
        }
        return content.toString();
    }

    /**
     * Unbuffered IO ile mesaj okur
     */
    private String getMessageUnbuffered(Path messageFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(messageFile.toFile())) {
            byte[] buffer = new byte[(int) Files.size(messageFile)];
            int bytesRead = fis.read(buffer);
            if (bytesRead == -1) {
                return "";
            }
            return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
        }
    }

    /**
     * Mesaj dosyasının path'ini döndürür
     * 
     * @param id Mesaj ID'si
     * @return Dosya path'i
     */
    private Path getMessageFilePath(Integer id) {
        String fileName = id + FILE_EXTENSION;
        return messagesDirectory.resolve(fileName);
    }

    /**
     * Mesajı siler
     * 
     * @param id Mesaj ID'si
     * @return Silme başarılı ise true
     * @throws IOException Dosya silme hatası
     */
    public boolean deleteMessage(Integer id) throws IOException {
        if (id == null) {
            throw new IllegalArgumentException("ID null olamaz");
        }

        Path messageFile = getMessageFilePath(id);
        
        if (!Files.exists(messageFile)) {
            return false;
        }

        Files.delete(messageFile);
        logger.debug("Mesaj silindi: id={}, dosya={}", id, messageFile);
        
        return true;
    }

    /**
     * Messages klasörünün path'ini döndürür
     */
    public Path getMessagesDirectory() {
        return messagesDirectory;
    }

    /**
     * Kullanılan IO modunu döndürür
     */
    public IOMode getIOMode() {
        return ioMode;
    }

    /**
     * Disk'teki mesaj dosyası sayısını döndürür
     * 
     * @return Mesaj dosyası sayısı
     * @throws IOException Dosya okuma hatası
     */
    public int getMessageCount() throws IOException {
        if (!Files.exists(messagesDirectory)) {
            return 0;
        }

        try {
            return (int) Files.list(messagesDirectory)
                    .filter(path -> path.toString().endsWith(FILE_EXTENSION))
                    .count();
        } catch (IOException e) {
            logger.error("Mesaj sayısı alınırken hata: ", e);
            throw e;
        }
    }
}
