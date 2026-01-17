package com.sistem.proje.protocol;

import com.sistem.proje.storage.IOMode;
import com.sistem.proje.storage.MessageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Komutları çalıştıran handler sınıfı
 * Disk tabanlı MessageStorage kullanır
 */
public class CommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);
    
    // Disk tabanlı storage
    private final MessageStorage storage;
    
    // Response mesajları
    public static final String OK = "OK";
    public static final String NOT_FOUND = "NOT_FOUND";

    /**
     * Varsayılan Buffered IO modu ile oluşturur
     */
    public CommandHandler() {
        this(IOMode.UNBUFFERED);
    }

    /**
     * Belirtilen IO modu ile oluşturur
     * 
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     */
    public CommandHandler(IOMode ioMode) {
        this.storage = new MessageStorage(ioMode);
    }

    /**
     * Komutu çalıştırır ve sonucu döndürür
     * 
     * @param command Çalıştırılacak komut
     * @return Komut sonucu (OK, NOT_FOUND veya hata mesajı)
     */
    public String execute(Command command) {
        if (command == null) {
            return "ERROR: Komut null olamaz";
        }

        try {
            switch (command.getType()) {
                case SET:
                    return executeSet((SetCommand) command);
                case GET:
                    return executeGet((GetCommand) command);
                default:
                    return "ERROR: Bilinmeyen komut tipi";
            }
        } catch (NumberFormatException e) {
            logger.error("ID parse hatası: {}", command.getId(), e);
            return "ERROR: Geçersiz ID formatı. ID bir sayı olmalıdır.";
        } catch (Exception e) {
            logger.error("Komut çalıştırma hatası: ", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * SET komutunu çalıştırır - Disk'e yazar
     */
    private String executeSet(SetCommand command) {
        try {
            Integer id = parseId(command.getId());
            String message = command.getMessage();
            
            storage.saveMessage(id, message);
            logger.debug("SET komutu: id={}, message={}", id, message);
            
            return OK;
        } catch (IOException e) {
            logger.error("SET komutu disk yazma hatası: ", e);
            return "ERROR: Disk yazma hatası: " + e.getMessage();
        }
    }

    /**
     * GET komutunu çalıştırır - Disk'ten okur
     */
    private String executeGet(GetCommand command) {
        try {
            Integer id = parseId(command.getId());
            
            String message = storage.getMessage(id);
            
            if (message == null) {
                logger.debug("GET komutu: id={}, sonuç=NOT_FOUND", id);
                return NOT_FOUND;
            }
            
            logger.debug("GET komutu: id={}, message={}", id, message);
            return message;
        } catch (IOException e) {
            logger.error("GET komutu disk okuma hatası: ", e);
            return "ERROR: Disk okuma hatası: " + e.getMessage();
        }
    }

    /**
     * String ID'yi Integer'a parse eder
     */
    private Integer parseId(String id) throws NumberFormatException {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("ID bir tam sayı olmalıdır: " + id);
        }
    }

    /**
     * MessageStorage instance'ını döndürür
     * 
     * @return MessageStorage instance'ı
     */
    public MessageStorage getStorage() {
        return storage;
    }

}

