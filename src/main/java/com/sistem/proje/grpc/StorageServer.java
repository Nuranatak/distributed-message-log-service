package com.sistem.proje.grpc;

import com.sistem.proje.storage.IOMode;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * gRPC Storage Server
 * StorageService'i expose eder ve client isteklerini kabul eder
 */
public class StorageServer {
    private static final Logger logger = LoggerFactory.getLogger(StorageServer.class);
    private static final int DEFAULT_PORT = 9090;
    
    private final Server server;
    private final int port;
    private final StorageServiceImpl serviceImpl;

    /**
     * Varsayılan port ve Buffered IO ile oluşturur
     */
    public StorageServer() {
        this(DEFAULT_PORT, IOMode.UNBUFFERED);
    }

    /**
     * Belirtilen port ve IO modu ile oluşturur
     * 
     * @param port Server port'u
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     */
    public StorageServer(int port, IOMode ioMode) {
        this.port = port;
        this.serviceImpl = new StorageServiceImpl(ioMode);
        // StorageServiceImpl, StorageServiceImplBase'den extend eder ve BindableService implement eder
        // addService metodu BindableService kabul eder
        this.server = ServerBuilder.forPort(port)
                .addService((BindableService) serviceImpl)
                .build();
    }

    /**
     * Server'ı başlatır
     * 
     * @throws IOException Server başlatma hatası
     */
    public void start() throws IOException {
        server.start();
        logger.info("gRPC Storage Server başlatıldı. Port: {}", port);

        // Shutdown hook ekle
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown sinyali alındı, server kapatılıyor...");
            try {
                StorageServer.this.stop();
            } catch (InterruptedException e) {
                logger.error("Server kapatılırken hata: ", e);
                Thread.currentThread().interrupt();
            }
        }));
    }

    /**
     * Server'ı durdurur
     * 
     * @throws InterruptedException Bekleme hatası
     */
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown();
            if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Server 30 saniye içinde kapanmadı, zorla kapatılıyor...");
                server.shutdownNow();
                if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("Server zorla kapatılamadı");
                }
            }
            logger.info("gRPC Storage Server durduruldu");
        }
    }

    /**
     * Server'ın çalışıp çalışmadığını kontrol eder
     */
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    /**
     * Server'ın kapanmasını bekler (blocking)
     * 
     * @throws InterruptedException Bekleme hatası
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main metodu - Server'ı başlatır
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        IOMode ioMode = IOMode.UNBUFFERED;

        // Port argümanı
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.error("Geçersiz port numarası: {}. Varsayılan port kullanılıyor: {}", 
                        args[0], DEFAULT_PORT);
            }
        }

        // IO modu argümanı
        if (args.length > 1) {
            try {
                ioMode = IOMode.valueOf(args[1].toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.error("Geçersiz IO modu: {}. Varsayılan BUFFERED kullanılıyor", args[1]);
            }
        }

        StorageServer server = new StorageServer(port, ioMode);

        try {
            server.start();
            logger.info("gRPC Storage Server çalışıyor. Port: {}, IO Modu: {}", port, ioMode);
            server.blockUntilShutdown();
        } catch (IOException e) {
            logger.error("Server başlatılamadı: ", e);
        } catch (InterruptedException e) {
            logger.error("Server beklenirken kesinti: ", e);
            Thread.currentThread().interrupt();
        }
    }
}

