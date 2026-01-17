package com.sistem.proje.member;

import com.sistem.proje.grpc.StorageServer;
import com.sistem.proje.storage.IOMode;
import com.sistem.proje.storage.MessageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
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
    
    // Heartbeat için
    private final String memberId;
    private final String leaderHost;
    private final int leaderPort;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 3; // Her 3 saniyede bir heartbeat gönder

    /**
     * Varsayılan ayarlarla oluşturur
     */
    public MemberNode() {
        this(DEFAULT_GRPC_PORT, IOMode.UNBUFFERED, DEFAULT_STATS_INTERVAL_SECONDS);
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
        this(grpcPort, ioMode, statsIntervalSeconds, "localhost", 6666, "member-" + grpcPort);
    }

    /**
     * Belirtilen port, IO modu, istatistik aralığı ve leader bilgileri ile oluşturur
     * 
     * @param grpcPort gRPC server port'u
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     * @param statsIntervalSeconds İstatistik yazdırma aralığı (saniye)
     * @param leaderHost Leader host adresi
     * @param leaderPort Leader port'u
     * @param memberId Member ID'si
     */
    public MemberNode(int grpcPort, IOMode ioMode, long statsIntervalSeconds, String leaderHost, int leaderPort, String memberId) {
        this.grpcPort = grpcPort;
        this.messageStorage = new MessageStorage(ioMode);
        this.storageServer = new StorageServer(grpcPort, ioMode);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.statsIntervalSeconds = statsIntervalSeconds > 0 ? statsIntervalSeconds : DEFAULT_STATS_INTERVAL_SECONDS;
        this.leaderHost = leaderHost;
        this.leaderPort = leaderPort;
        this.memberId = memberId;
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
            
            // Heartbeat göndermeyi başlat
            startHeartbeat();
            
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
     * Heartbeat göndermeyi başlatır
     */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(
            this::sendHeartbeat,
            HEARTBEAT_INTERVAL_SECONDS, // İlk heartbeat'i hemen gönderme, biraz bekle
            HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        logger.info("Heartbeat gönderme başlatıldı. Aralık: {} saniye", HEARTBEAT_INTERVAL_SECONDS);
    }

    /**
     * Leader'a heartbeat mesajı gönderir
     */
    private void sendHeartbeat() {
        if (!running) {
            return;
        }

        try (Socket socket = new Socket(leaderHost, leaderPort);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            String heartbeatCmd = String.format("HEARTBEAT %s", memberId);
            writer.println(heartbeatCmd);
            
            String response = reader.readLine();
            if ("HEARTBEAT_OK".equals(response)) {
                logger.debug("Heartbeat sent successfully to leader: {}", memberId);
            } else {
                logger.warn("Heartbeat response unexpected: {}", response);
            }
        } catch (IOException e) {
            logger.debug("Heartbeat gönderilemedi ({}:{}): {}", leaderHost, leaderPort, e.getMessage());
            // Hata durumunda sessizce devam et, bir sonraki heartbeat'te tekrar dener
        }
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
        // Port belirleme: 1) System property, 2) Varsayılan
        int port = DEFAULT_GRPC_PORT;
        String portProperty = System.getProperty("member.port");
        if (portProperty != null && !portProperty.isEmpty()) {
            try {
                port = Integer.parseInt(portProperty);
                logger.info("Port system property'den okundu: {}", port);
            } catch (NumberFormatException e) {
                logger.error("Geçersiz member.port değeri: {}. Varsayılan port kullanılıyor: {}", 
                        portProperty, DEFAULT_GRPC_PORT);
            }
        }

        IOMode ioMode = IOMode.UNBUFFERED;
        long statsInterval = DEFAULT_STATS_INTERVAL_SECONDS;
        
        // Leader bilgileri
        String leaderHost = "localhost";
        int leaderPort = 6666;
        String memberId = "member-" + port;

        logger.info("=== MemberNode başlatılıyor === Port: {}", port);

        // MemberNode'u leader bilgileri ile oluştur
        MemberNode member = new MemberNode(port, ioMode, statsInterval, leaderHost, leaderPort, memberId);
        
        // Shutdown hook ekle
        final int finalPort = port;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown sinyali alındı...");
            member.stop();
        }));

        member.start();
        
        // LeaderNode'a register ol
        registerToLeader(leaderHost, leaderPort, memberId, "localhost", finalPort);
        
        // Server'ın çalışmasını bekle
        try {
            member.storageServer.blockUntilShutdown();
        } catch (InterruptedException e) {
            logger.error("Member Node beklenirken kesinti: ", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * LeaderNode'a TCP üzerinden REGISTER mesajı gönderir
     */
    private static void registerToLeader(String leaderHost, int leaderPort, String memberId, String memberHost, int memberPort) {
        try (Socket socket = new Socket(leaderHost, leaderPort);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            String registerCmd = String.format("REGISTER %s %s %d", memberId, memberHost, memberPort);
            writer.println(registerCmd);
            
            String response = reader.readLine();
            if ("REGISTERED".equals(response)) {
                logger.info("Registered to leader as {}:{}", memberHost, memberPort);
                System.out.println(String.format("Registered to leader as %s:%d", memberHost, memberPort));
            } else {
                logger.warn("Leader registration failed: {}", response);
            }
        } catch (IOException e) {
            logger.error("Leader'a bağlanılamadı ({}:{}): {}", leaderHost, leaderPort, e.getMessage());
            System.err.println("UYARI: Leader'a bağlanılamadı. Leader çalışıyor mu?");
        }
    }
}

