package com.sistem.proje.grpc;

import com.sistem.proje.storage.IOMode;
import com.sistem.proje.storage.MessageStorage;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * gRPC StorageService implementasyonu
 * MessageStorage kullanarak disk tabanlı mesaj saklama sağlar
 */
public class StorageServiceImpl extends StorageServiceGrpc.StorageServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(StorageServiceImpl.class);
    
    private final MessageStorage messageStorage;

    /**
     * Varsayılan Buffered IO modu ile oluşturur
     */
    public StorageServiceImpl() {
        this(IOMode.BUFFERED);
    }

    /**
     * Belirtilen IO modu ile oluşturur
     * 
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     */
    public StorageServiceImpl(IOMode ioMode) {
        this.messageStorage = new MessageStorage(ioMode);
        logger.info("StorageServiceImpl başlatıldı. IO Modu: {}", ioMode);
    }

    /**
     * Mesajı saklar (Store RPC)
     */
    @Override
    public void store(StoredMessage request, StreamObserver<StoreResult> responseObserver) {
        try {
            Integer id = request.getId();
            String text = request.getText();

            logger.debug("Store RPC çağrıldı: id={}, text={}", id, text);

            // Mesajı disk'e kaydet
            messageStorage.saveMessage(id, text);

            // Başarılı sonuç döndür
            StoreResult result = StoreResult.newBuilder()
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(result);
            responseObserver.onCompleted();

            logger.debug("Store RPC tamamlandı: id={}, success=true", id);

        } catch (IOException e) {
            logger.error("Store RPC hatası: ", e);
            
            // Hata sonucu döndür
            StoreResult result = StoreResult.newBuilder()
                    .setSuccess(false)
                    .build();

            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Store RPC beklenmeyen hata: ", e);
            responseObserver.onError(e);
        }
    }

    /**
     * Mesajı getirir (Retrieve RPC)
     */
    @Override
    public void retrieve(MessageId request, StreamObserver<StoredMessage> responseObserver) {
        try {
            Integer id = request.getId();

            logger.debug("Retrieve RPC çağrıldı: id={}", id);

            // Mesajı disk'ten oku
            String text = messageStorage.getMessage(id);

            if (text == null) {
                // Mesaj bulunamadı - boş mesaj döndür
                logger.debug("Retrieve RPC: Mesaj bulunamadı: id={}", id);
                StoredMessage result = StoredMessage.newBuilder()
                        .setId(id)
                        .setText("")
                        .build();

                responseObserver.onNext(result);
                responseObserver.onCompleted();
            } else {
                // Mesaj bulundu
                StoredMessage result = StoredMessage.newBuilder()
                        .setId(id)
                        .setText(text)
                        .build();

                responseObserver.onNext(result);
                responseObserver.onCompleted();

                logger.debug("Retrieve RPC tamamlandı: id={}, text length={}", id, text.length());
            }

        } catch (IOException e) {
            logger.error("Retrieve RPC hatası: ", e);
            responseObserver.onError(e);
        } catch (Exception e) {
            logger.error("Retrieve RPC beklenmeyen hata: ", e);
            responseObserver.onError(e);
        }
    }
}

