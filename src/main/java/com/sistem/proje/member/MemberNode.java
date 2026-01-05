package com.sistem.proje.member;

import com.sistem.proje.grpc.StorageServer;
import com.sistem.proje.storage.IOMode;
import com.sistem.proje.storage.MessageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Member Node - gRPC server olarak çalışır ve mesajları saklar
 * Periyodik olarak diskteki mesaj sayısını console'a basar
 */
public class MemberNode {
    private static final Logger logger = LoggerFactory.getLogger(MemberNode.class);
    private static final int DEFAULT_GRPC_PORT = 9090;
    private static final long DEFAULT_STATS_INTERVAL_SECONDS = 10;
    
    private final int grpcPort;
    private final MessageStorage messageStorage;
    private final StorageServer storageServer;
    private final ScheduledExecutorService scheduler;
    private final long statsIntervalSeconds;
    private volatile boolean running = false;

    /**
     * Varsayılan ayarlarla oluşturur
     */
    public MemberNode() {
        this(DEFAULT_GRPC_PORT, IOMode.BUFFERED, DEFAULT_STATS_INTERVAL_SECONDS);
    }

    /**
     * Belirtilen port ve IO modu ile oluşturur
     * 
     * @param grpcPort gRPC server port'u
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     */
    public MemberNode(int grpcPort, IOMode ioMode) {
        this(grpcPort, ioMode, DEFAULT_STATS_INTERVAL_SECONDS);
    }

    /**
     * Belirtilen port, IO modu ve istatistik aralığı ile oluşturur
     * 
     * @param grpcPort gRPC server port'u
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     * @param statsIntervalSeconds İstatistik yazdırma aralığı (saniye)
     */
    public MemberNode(int grpcPort, IOMode ioMode, long statsIntervalSeconds) {
        this.grpcPort = grpcPort;
        this.messageStorage = new MessageStorage(ioMode);
        this.storageServer = new StorageServer(grpcPort, ioMode);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.statsIntervalSeconds = statsIntervalSeconds > 0 ? statsIntervalSeconds : DEFAULT_STATS_INTERVAL_SECONDS;
    }

    /**
     * Member node'u başlatır
     */
    public void start() {
        try {
            running = true;
            storageServer.start();
            logger.info("Member Node başlatıldı. gRPC Port: {}", grpcPort);
            
            // Periyodik istatistik yazdırmayı başlat
            startPeriodicStats();
            
        } catch (IOException e) {
            logger.error("Member Node başlatılamadı: ", e);
            throw new RuntimeException("Member Node başlatılamadı", e);
        }
    }

    /**
     * Member node'u durdurur
     */
    public void stop() {
        running = false;
        try {
            storageServer.stop();
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            logger.info("Member Node durduruldu.");
        } catch (InterruptedException e) {
            logger.error("Member Node kapatılırken hata: ", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Periyodik istatistik yazdırmayı başlatır
     */
    private void startPeriodicStats() {
        scheduler.scheduleAtFixedRate(
            this::printMessageCount,
            0, // İlk çalıştırma gecikmesi (0 = hemen)
            statsIntervalSeconds,
            TimeUnit.SECONDS
        );
        logger.info("Periyodik istatistik yazdırma başlatıldı. Aralık: {} saniye", statsIntervalSeconds);
    }

    /**
     * Disk'teki mesaj sayısını sayar ve console'a yazdırır
     */
    private void printMessageCount() {
        if (!running) {
            return;
        }

        try {
            int messageCount = messageStorage.getMessageCount();
            String message = String.format(
                "[MEMBER STATS] Port: %d | Disk'teki mesaj sayısı: %d | Klasör: %s",
                grpcPort,
                messageCount,
                messageStorage.getMessagesDirectory().toAbsolutePath()
            );
            
            // Console'a yazdır
            System.out.println(message);
            logger.debug(message);
            
        } catch (IOException e) {
            String errorMsg = String.format(
                "[MEMBER STATS ERROR] Port: %d | Mesaj sayısı alınamadı: %s",
                grpcPort,
                e.getMessage()
            );
            System.err.println(errorMsg);
            logger.error("Mesaj sayısı alınırken hata: ", e);
        }
    }

    /**
     * Disk'teki mesaj sayısını döndürür (manuel sorgulama için)
     * 
     * @return Mesaj sayısı
     * @throws IOException Dosya okuma hatası
     */
    public int getMessageCount() throws IOException {
        return messageStorage.getMessageCount();
    }

    /**
     * gRPC port'unu döndürür
     */
    public int getGrpcPort() {
        return grpcPort;
    }

    /**
     * Main metodu - Member Node'u başlatır
     */
    public static void main(String[] args) {
        int port = DEFAULT_GRPC_PORT;
        IOMode ioMode = IOMode.BUFFERED;
        long statsInterval = DEFAULT_STATS_INTERVAL_SECONDS;

        // Port argümanı
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.error("Geçersiz port numarası: {}. Varsayılan port kullanılıyor: {}", 
                        args[0], DEFAULT_GRPC_PORT);
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

        // İstatistik aralığı argümanı
        if (args.length > 2) {
            try {
                statsInterval = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                logger.error("Geçersiz istatistik aralığı: {}. Varsayılan {} saniye kullanılıyor", 
                        args[2], DEFAULT_STATS_INTERVAL_SECONDS);
            }
        }

        MemberNode member = new MemberNode(port, ioMode, statsInterval);
        
        // Shutdown hook ekle
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown sinyali alındı...");
            member.stop();
        }));

        member.start();
        
        // Server'ın çalışmasını bekle
        try {
            member.storageServer.blockUntilShutdown();
        } catch (InterruptedException e) {
            logger.error("Member Node beklenirken kesinti: ", e);
            Thread.currentThread().interrupt();
        }
    }
}

