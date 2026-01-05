package com.sistem.proje.leader;

import com.sistem.proje.config.ConfigException;
import com.sistem.proje.config.ConfigLoader;
import com.sistem.proje.grpc.StorageServiceGrpc;
import com.sistem.proje.grpc.StoredMessage;
import com.sistem.proje.grpc.StoreResult;
import com.sistem.proje.protocol.Command;
import com.sistem.proje.protocol.CommandHandler;
import com.sistem.proje.protocol.CommandParseException;
import com.sistem.proje.protocol.CommandParser;
import com.sistem.proje.protocol.GetCommand;
import com.sistem.proje.protocol.SetCommand;
import com.sistem.proje.storage.IOMode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Leader Node - TCP client isteklerini kabul eder ve SET/GET işlemlerini yönetir
 */
public class LeaderNode {
    private static final Logger logger = LoggerFactory.getLogger(LeaderNode.class);
    private static final int DEFAULT_PORT = 8080;
    
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService clientThreadPool;
    private volatile boolean running = false;
    
    // Komut işleme bileşenleri
    private final CommandParser commandParser;
    private final CommandHandler commandHandler;
    
    // Konfigürasyon
    private final ConfigLoader configLoader;
    private int tolerance;
    
    // Üye listeleri (memory'de tutulur)
    private final List<MemberInfo> activeMembers;  // Aktif üyeler
    private final List<MemberInfo> deadMembers;    // Ölü üyeler
    
    // Mesaj ID → hangi üyelerde saklandığını tutar
    // Key: mesaj ID (Integer), Value: üye ID listesi (List<String>)
    private final Map<Integer, List<String>> messageToMembers;
    
    // Load balancing stratejisi
    private final LoadBalancingStrategy loadBalancingStrategy;
    
    // Round-robin için counter (thread-safe)
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    
    // Üye seçimi için random (fallback için)
    private final Random random;
    
    // Periyodik istatistik için scheduler
    private final ScheduledExecutorService statsScheduler;
    private static final long DEFAULT_STATS_INTERVAL_SECONDS = 10;

    /**
     * Varsayılan port, Buffered IO ve Hash-based load balancing ile oluşturur
     */
    public LeaderNode() {
        this(DEFAULT_PORT, IOMode.BUFFERED, LoadBalancingStrategy.HASH_BASED);
    }

    /**
     * Belirtilen port ve IO modu ile oluşturur (varsayılan Hash-based load balancing)
     * 
     * @param port TCP server port'u
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     */
    public LeaderNode(int port, IOMode ioMode) {
        this(port, ioMode, LoadBalancingStrategy.HASH_BASED);
    }

    /**
     * Belirtilen port, IO modu ve load balancing stratejisi ile oluşturur
     * 
     * @param port TCP server port'u
     * @param ioMode IO modu (BUFFERED veya UNBUFFERED)
     * @param loadBalancingStrategy Load balancing stratejisi (ROUND_ROBIN veya HASH_BASED)
     */
    public LeaderNode(int port, IOMode ioMode, LoadBalancingStrategy loadBalancingStrategy) {
        this.port = port;
        this.clientThreadPool = Executors.newCachedThreadPool();
        this.commandParser = new CommandParser();
        this.commandHandler = new CommandHandler(ioMode);
        this.configLoader = new ConfigLoader();
        this.loadBalancingStrategy = loadBalancingStrategy != null ? loadBalancingStrategy : LoadBalancingStrategy.HASH_BASED;
        this.activeMembers = new CopyOnWriteArrayList<>();
        this.deadMembers = new CopyOnWriteArrayList<>();
        this.messageToMembers = new ConcurrentHashMap<>();
        this.random = new Random();
        // roundRobinCounter zaten field'da initialize edilmiş (final)
        this.tolerance = 0;
        this.statsScheduler = Executors.newScheduledThreadPool(1);
        
        // Tolerance değerini yükle
        try {
            configLoader.load();
            this.tolerance = configLoader.getTolerance();
            logger.info("Tolerance değeri yüklendi: {} (desteklenen aralık: 1-7)", tolerance);
            logger.info("Load balancing stratejisi: {}", loadBalancingStrategy);
        } catch (IOException | ConfigException e) {
            // ConfigLoader artık dosya yoksa default değer kullanıyor, bu catch bloğu sadece parse hataları için
            logger.error("Tolerance değeri yüklenirken hata: {}", e.getMessage());
            // Sistem çökmesin, default değeri kullan
            this.tolerance = ConfigLoader.getDefaultTolerance();
            logger.warn("Hata nedeniyle default tolerance değeri kullanılıyor: {}", this.tolerance);
        }
    }

    /**
     * Leader node'u başlatır
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            logger.info("Leader Node başlatıldı. Port: {}", port);

            // Periyodik istatistikleri başlat
            startPeriodicStats();

            // Aktif üye listesini logla
            logRegisteredMembers();

            while (running) {
                Socket clientSocket = serverSocket.accept();
                logger.info("Yeni client bağlandı: {}", clientSocket.getRemoteSocketAddress());
                
                // Her client için ayrı thread'de işle
                clientThreadPool.submit(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            if (running) {
                logger.error("Leader Node hatası: ", e);
            }
        }
    }

    /**
     * Kayıtlı üye listesini loglar
     */
    private void logRegisteredMembers() {
        if (activeMembers.isEmpty() && deadMembers.isEmpty()) {
            logger.warn("Hiç üye kayıtlı değil. Üye eklemek için addMember() metodunu kullanın.");
        } else {
            logger.info("=== Kayıtlı Üye Listesi ===");
            for (MemberInfo member : activeMembers) {
                logger.info("Member registered: {} ({}:{}) [ACTIVE]", member.getId(), member.getHost(), member.getPort());
            }
            for (MemberInfo member : deadMembers) {
                logger.info("Member registered: {} ({}:{}) [DEAD]", member.getId(), member.getHost(), member.getPort());
            }
            logger.info("Toplam {} aktif, {} ölü üye kayıtlı", activeMembers.size(), deadMembers.size());
            logger.info("===========================");
        }
    }

    /**
     * Leader node'u durdurur
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            clientThreadPool.shutdown();
            statsScheduler.shutdown();
            if (!statsScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                statsScheduler.shutdownNow();
            }
            logger.info("Leader Node durduruldu.");
        } catch (IOException e) {
            logger.error("Leader Node kapatılırken hata: ", e);
        } catch (InterruptedException e) {
            logger.error("Stats scheduler kapatılırken hata: ", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Üye ekler (aktif üyeler listesine)
     * 
     * @param memberId Üye ID'si
     * @param host Üye host adresi
     * @param port Üye port'u
     */
    public void addMember(String memberId, String host, int port) {
        MemberInfo member = new MemberInfo(memberId, host, port);
        // Aynı ID'ye sahip üye varsa önce kaldır
        activeMembers.removeIf(m -> m.getId().equals(memberId));
        deadMembers.removeIf(m -> m.getId().equals(memberId));
        // Aktif üyeler listesine ekle
        activeMembers.add(member);
        logger.info("Member registered: {} ({}:{})", memberId, host, port);
        System.out.println(String.format("Member registered: %s (%s:%d)", memberId, host, port));
    }

    /**
     * Üye kaldırır (hem aktif hem ölü listeden)
     * 
     * @param memberId Üye ID'si
     */
    public void removeMember(String memberId) {
        boolean removedFromActive = activeMembers.removeIf(m -> m.getId().equals(memberId));
        boolean removedFromDead = deadMembers.removeIf(m -> m.getId().equals(memberId));
        if (removedFromActive || removedFromDead) {
            logger.info("Üye kaldırıldı: {}", memberId);
        }
    }

    /**
     * Aktif üye listesini döndürür
     * 
     * @return Aktif üye listesi (read-only)
     */
    public List<MemberInfo> getActiveMembers() {
        return new ArrayList<>(activeMembers);
    }

    /**
     * Ölü üye listesini döndürür
     * 
     * @return Ölü üye listesi (read-only)
     */
    public List<MemberInfo> getDeadMembers() {
        return new ArrayList<>(deadMembers);
    }

    /**
     * Tüm üye listesini döndürür (aktif + ölü)
     * 
     * @return Tüm üye listesi (read-only)
     */
    public List<MemberInfo> getAllMembers() {
        List<MemberInfo> all = new ArrayList<>(activeMembers);
        all.addAll(deadMembers);
        return all;
    }

    /**
     * Aktif üye sayısını döndürür
     */
    public int getActiveMemberCount() {
        return activeMembers.size();
    }

    /**
     * Toplam üye sayısını döndürür (aktif + ölü)
     */
    public int getTotalMemberCount() {
        return activeMembers.size() + deadMembers.size();
    }

    /**
     * Mesajın hangi üyelerde saklandığını kaydeder
     * 
     * @param messageId Mesaj ID'si
     * @param memberId Üye ID'si
     */
    public void addMessageToMember(Integer messageId, String memberId) {
        messageToMembers.computeIfAbsent(messageId, k -> new CopyOnWriteArrayList<>()).add(memberId);
        logger.debug("Mesaj {} üye {}'ye eklendi", messageId, memberId);
    }

    /**
     * Mesajın üyeden kaldırıldığını kaydeder
     * 
     * @param messageId Mesaj ID'si
     * @param memberId Üye ID'si
     */
    public void removeMessageFromMember(Integer messageId, String memberId) {
        List<String> memberList = messageToMembers.get(messageId);
        if (memberList != null) {
            memberList.remove(memberId);
            // Eğer liste boşaldıysa map'ten kaldır
            if (memberList.isEmpty()) {
                messageToMembers.remove(messageId);
            }
            logger.debug("Mesaj {} üye {}'den kaldırıldı", messageId, memberId);
        }
    }

    /**
     * Mesajın hangi üyelerde saklandığını döndürür
     * 
     * @param messageId Mesaj ID'si
     * @return Üye ID listesi (read-only), mesaj yoksa boş liste
     */
    public List<String> getMembersForMessage(Integer messageId) {
        List<String> memberList = messageToMembers.get(messageId);
        if (memberList == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(memberList);
    }

    /**
     * Mesajın belirli bir üyede saklanıp saklanmadığını kontrol eder
     * 
     * @param messageId Mesaj ID'si
     * @param memberId Üye ID'si
     * @return Mesaj üyede saklanıyorsa true
     */
    public boolean isMessageInMember(Integer messageId, String memberId) {
        List<String> memberList = messageToMembers.get(messageId);
        return memberList != null && memberList.contains(memberId);
    }

    /**
     * Mesajın kaç üyede saklandığını döndürür
     * 
     * @param messageId Mesaj ID'si
     * @return Üye sayısı
     */
    public int getMemberCountForMessage(Integer messageId) {
        List<String> memberList = messageToMembers.get(messageId);
        return memberList != null ? memberList.size() : 0;
    }

    /**
     * Tüm mesaj-üye eşleşmelerini temizler
     */
    public void clearMessageToMembers() {
        messageToMembers.clear();
        logger.debug("Mesaj-üye eşleşmeleri temizlendi");
    }

    /**
     * Periyodik istatistik yazdırmayı başlatır
     */
    private void startPeriodicStats() {
        statsScheduler.scheduleAtFixedRate(
            this::printStats,
            0, // İlk çalıştırma gecikmesi (0 = hemen)
            DEFAULT_STATS_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        logger.info("Periyodik istatistik yazdırma başlatıldı. Aralık: {} saniye", DEFAULT_STATS_INTERVAL_SECONDS);
    }

    /**
     * İstatistikleri hesaplar ve console'a yazdırır:
     * - Toplam mesaj sayısı (lider diskinde)
     * - Her üyenin kaç mesaj tuttuğu (messageToMembers map'inden)
     */
    private void printStats() {
        if (!running) {
            return;
        }

        try {
            // Lider diskindeki toplam mesaj sayısı
            int leaderMessageCount = commandHandler.getStorage().getMessageCount();
            
            // Her üyenin mesaj sayısını hesapla (messageToMembers map'inden)
            Map<String, Integer> memberMessageCounts = new ConcurrentHashMap<>();
            for (Map.Entry<Integer, List<String>> entry : messageToMembers.entrySet()) {
                for (String memberId : entry.getValue()) {
                    memberMessageCounts.merge(memberId, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
                }
            }
            
            // Beklenen formatta console'a yazdır
            System.out.println("[STATS]");
            System.out.println(String.format("Total messages: %d", leaderMessageCount));
            
            if (activeMembers.isEmpty() && deadMembers.isEmpty()) {
                System.out.println("(No members)");
            } else {
                // Sadece aktif üyeleri yazdır
                for (MemberInfo member : activeMembers) {
                    int count = memberMessageCounts.getOrDefault(member.getId(), 0);
                    System.out.println(String.format("Member %s: %d", member.getId(), count));
                }
            }
            System.out.println(); // Boş satır
            
            logger.debug("İstatistikler yazdırıldı. Lider: {}, Üyeler: {}", 
                    leaderMessageCount, memberMessageCounts);
            
        } catch (IOException e) {
            String errorMsg = String.format(
                "[STATS ERROR] Mesaj sayısı alınamadı: %s",
                e.getMessage()
            );
            System.err.println(errorMsg);
            logger.error("İstatistik yazdırılırken hata: ", e);
        } catch (Exception e) {
            String errorMsg = String.format(
                "[STATS ERROR] Beklenmeyen hata: %s",
                e.getMessage()
            );
            System.err.println(errorMsg);
            logger.error("İstatistik yazdırılırken beklenmeyen hata: ", e);
        }
    }

    /**
     * SET komutunu işler:
     * 1. Lider mesajı kendi diskine kaydeder
     * 2. Tolerance kadar üye seçer
     * 3. Seçilen üyelere gRPC Store çağrısı yapar
     * 4. Tüm üyeler başarılıysa OK, herhangi biri başarısızsa ERROR döner
     */
    private String handleSetCommand(SetCommand command) {
        try {
            Integer messageId = Integer.parseInt(command.getId());
            String message = command.getMessage();

            logger.info("SET komutu işleniyor: id={}, message length={}", messageId, message.length());

            // 1. Lider mesajı kendi diskine kaydet
            String leaderStoreResult = commandHandler.execute(command);
            if (!leaderStoreResult.equals(CommandHandler.OK)) {
                logger.error("SET komutu: Lider diskine kayıt başarısız: {}", leaderStoreResult);
                return "ERROR: Lider diskine kayıt başarısız: " + leaderStoreResult;
            }
            logger.debug("Mesaj lider diskine kaydedildi: id={}", messageId);
            
            // Lider mesajı tuttuğu için messageToMembers map'ine ekle
            addMessageToMember(messageId, "leader");
            logger.debug("Mesaj {} lider'e eklendi (messageToMembers map)", messageId);

            // 2. Tolerance kadar üye seç (load balancing stratejisine göre)
            List<MemberInfo> selectedMembers = selectMembers(tolerance, messageId);
            
            if (selectedMembers.isEmpty()) {
                logger.warn("SET komutu: Üye bulunamadı, sadece lider diskine kaydedildi");
                return CommandHandler.OK;
            }

            logger.info("SET komutu: {} üye seçildi", selectedMembers.size());

            // 3. Seçilen üyelere gRPC Store çağrısı yap
            boolean allSuccess = true;
            List<String> successfulMembers = new ArrayList<>();
            List<String> crashedMembers = new ArrayList<>();

            for (MemberInfo member : selectedMembers) {
                try {
                    boolean success = storeMessageToMember(messageId, message, member);
                    if (success) {
                        successfulMembers.add(member.getId());
                        addMessageToMember(messageId, member.getId());
                        String successLog = String.format(
                            "[SET SUCCESS] Mesaj üye %s (%s:%d)'ye kaydedildi | Mesaj ID: %d",
                            member.getId(), member.getHost(), member.getPort(), messageId
                        );
                        logger.info(successLog);
                        System.out.println(successLog);
                    } else {
                        allSuccess = false;
                        logger.warn("Mesaj {} üye {}'ye kaydedilemedi", messageId, member.getId());
                    }
                } catch (Exception e) {
                    allSuccess = false;
                    // storeMessageToMember içinde üye zaten DEAD olarak işaretlenir
                    if (member.isDead()) {
                        crashedMembers.add(member.getId());
                    }
                    logger.error("Mesaj {} üye {}'ye kaydedilirken hata: ", messageId, member.getId(), e);
                }
            }

            // Crash olan üyeler varsa logla
            if (!crashedMembers.isEmpty()) {
                String crashLog = String.format(
                    "[SET CRASH] Mesaj kaydedilirken %d üye crash oldu: %s | Mesaj ID: %d",
                    crashedMembers.size(), String.join(", ", crashedMembers), messageId
                );
                logger.warn(crashLog);
                System.out.println(crashLog);
            }

            // 4. Başarı kontrolü
            if (allSuccess && successfulMembers.size() == selectedMembers.size()) {
                logger.info("SET komutu başarılı: id={}, {} üyede saklandı", messageId, successfulMembers.size());
                return CommandHandler.OK;
            } else {
                logger.warn("SET komutu kısmen başarısız: id={}, {}/{} üyede saklandı", 
                        messageId, successfulMembers.size(), selectedMembers.size());
                // Başarısız üyelerden kayıtları temizle
                for (MemberInfo member : selectedMembers) {
                    if (!successfulMembers.contains(member.getId())) {
                        removeMessageFromMember(messageId, member.getId());
                    }
                }
                return "ERROR: Bazı üyelere kayıt başarısız";
            }

        } catch (NumberFormatException e) {
            logger.error("SET komutu: Geçersiz ID formatı: {}", command.getId(), e);
            return "ERROR: Geçersiz ID formatı";
        } catch (Exception e) {
            logger.error("SET komutu: Beklenmeyen hata", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Tolerance kadar üye seçer (load balancing stratejisine göre)
     * Sadece aktif üyelerden seçim yapar
     * 
     * @param count Seçilecek üye sayısı
     * @param messageId Mesaj ID'si (hash-based için gerekli)
     * @return Seçilen üye listesi
     */
    private List<MemberInfo> selectMembers(int count, Integer messageId) {
        if (activeMembers.isEmpty() || count <= 0) {
            return Collections.emptyList();
        }

        // Mevcut aktif üye sayısından fazla istenirse, tüm aktif üyeleri döndür
        int selectCount = Math.min(count, activeMembers.size());

        List<MemberInfo> selectedMembers = new ArrayList<>();

        switch (loadBalancingStrategy) {
            case ROUND_ROBIN:
                selectedMembers = selectMembersRoundRobin(selectCount);
                break;
            case HASH_BASED:
                selectedMembers = selectMembersHashBased(selectCount, messageId);
                break;
            default:
                // Fallback: random seçim
                selectedMembers = selectMembersRandom(selectCount);
                break;
        }

        return selectedMembers;
    }

    /**
     * Round-robin ile üye seçer (sadece ALIVE üyeler)
     * Thread-safe counter kullanır
     * 
     * @param count Seçilecek üye sayısı
     * @return Seçilen üye listesi
     */
    private List<MemberInfo> selectMembersRoundRobin(int count) {
        List<MemberInfo> aliveMembers = getAliveMembers();
        if (aliveMembers.isEmpty()) {
            return Collections.emptyList();
        }

        List<MemberInfo> selected = new ArrayList<>();
        int memberCount = aliveMembers.size();
        
        // Thread-safe counter'dan başlangıç değerini al
        int startCounter = roundRobinCounter.get();
        
        for (int i = 0; i < count; i++) {
            int index = (startCounter + i) % memberCount;
            selected.add(aliveMembers.get(index));
        }
        
        // Counter'ı thread-safe şekilde güncelle
        int newCounter = (startCounter + count) % memberCount;
        roundRobinCounter.set(newCounter);
        
        logger.debug("Round-robin üye seçimi: counter={}, seçilen üyeler={}", 
                newCounter, selected.size());
        
        return selected;
    }

    /**
     * Hash-based (message_id % member_count) ile üye seçer (sadece ALIVE üyeler)
     * 
     * @param count Seçilecek üye sayısı
     * @param messageId Mesaj ID'si
     * @return Seçilen üye listesi
     */
    private List<MemberInfo> selectMembersHashBased(int count, Integer messageId) {
        List<MemberInfo> aliveMembers = getAliveMembers();
        if (aliveMembers.isEmpty()) {
            return Collections.emptyList();
        }

        List<MemberInfo> selected = new ArrayList<>();
        int memberCount = aliveMembers.size();

        // İlk üyeyi hash ile seç
        int startIndex = Math.abs(messageId % memberCount);
        
        // Tolerance kadar üyeyi sırayla seç (circular)
        for (int i = 0; i < count; i++) {
            int index = (startIndex + i) % memberCount;
            selected.add(aliveMembers.get(index));
        }
        
        logger.debug("Hash-based üye seçimi: messageId={}, startIndex={}, seçilen üyeler={}", 
                messageId, startIndex, selected.size());
        
        return selected;
    }

    /**
     * Random ile üye seçer (fallback, sadece ALIVE üyeler)
     * 
     * @param count Seçilecek üye sayısı
     * @return Seçilen üye listesi
     */
    private List<MemberInfo> selectMembersRandom(int count) {
        List<MemberInfo> aliveMembers = getAliveMembers();
        if (aliveMembers.isEmpty()) {
            return Collections.emptyList();
        }
        List<MemberInfo> shuffled = new ArrayList<>(aliveMembers);
        Collections.shuffle(shuffled, random);
        int selectCount = Math.min(count, shuffled.size());
        return shuffled.subList(0, selectCount);
    }

    /**
     * ALIVE durumundaki üyeleri döndürür (aktif üyeler listesi)
     * 
     * @return ALIVE üye listesi
     */
    private List<MemberInfo> getAliveMembers() {
        return new ArrayList<>(activeMembers);
    }

    /**
     * Üyeyi DEAD olarak işaretler ve deadMembers listesine taşır
     * 
     * @param memberId Üye ID'si
     * @param reason Ölüm nedeni (opsiyonel)
     */
    private void markMemberAsDead(String memberId, String reason) {
        MemberInfo member = findMemberById(memberId);
        if (member != null && activeMembers.contains(member)) {
            // Aktif listeden çıkar
            activeMembers.remove(member);
            // Status'u DEAD yap
            member.setStatus(MemberStatus.DEAD);
            // Ölü listesine ekle
            deadMembers.add(member);
            
            String logMessage = String.format("Member %s marked as DEAD", memberId);
            logger.warn(logMessage);
            System.out.println(logMessage);
            
            // Detaylı log
            logger.debug("Üye öldü: {} ({}:{}) | Neden: {}", 
                    memberId, member.getHost(), member.getPort(), reason != null ? reason : "Bilinmeyen");
        }
    }

    /**
     * Üyeyi DEAD olarak işaretler (neden belirtilmeden)
     */
    private void markMemberAsDead(String memberId) {
        markMemberAsDead(memberId, "Bağlantı hatası");
    }

    /**
     * Üyeyi ALIVE olarak işaretler ve activeMembers listesine taşır (recovery için)
     * 
     * @param memberId Üye ID'si
     */
    public void markMemberAsAlive(String memberId) {
        MemberInfo member = findMemberById(memberId);
        if (member != null && deadMembers.contains(member)) {
            // Ölü listeden çıkar
            deadMembers.remove(member);
            // Status'u ALIVE yap
            member.setStatus(MemberStatus.ALIVE);
            // Aktif listesine ekle
            activeMembers.add(member);
            logger.info("Member {} marked as ALIVE", memberId);
            logger.debug("Üye ALIVE olarak işaretlendi: {}", member);
        }
    }

    /**
     * GET komutunu işler:
     * 1. Liderin diskinde varsa direkt oku
     * 2. Yoksa map'te kayıtlı üyelere sırayla gRPC Retrieve çağrısı yap
     * 3. İlk başarılı cevabı client'a dön
     */
    private String handleGetCommand(GetCommand command) {
        try {
            Integer messageId = Integer.parseInt(command.getId());

            logger.info("GET komutu işleniyor: id={}", messageId);

            // 1. Liderin diskinde kontrol et
            String result = commandHandler.execute(command);
            
            if (!result.equals(CommandHandler.NOT_FOUND)) {
                logger.debug("GET komutu: Mesaj lider diskinde bulundu: id={}", messageId);
                return result;
            }

            logger.debug("GET komutu: Mesaj lider diskinde bulunamadı, üyelerde aranıyor: id={}", messageId);

            // 2. Map'te kayıtlı üyeleri bul (sadece ALIVE üyeler)
            List<String> memberIds = getMembersForMessage(messageId);
            
            if (memberIds.isEmpty()) {
                logger.debug("GET komutu: Mesaj hiçbir üyede kayıtlı değil: id={}", messageId);
                return CommandHandler.NOT_FOUND;
            }

            // ALIVE üyeleri filtrele
            List<MemberInfo> aliveMembersToCheck = new ArrayList<>();
            for (String memberId : memberIds) {
                MemberInfo member = findMemberById(memberId);
                if (member != null && member.isAlive()) {
                    aliveMembersToCheck.add(member);
                } else if (member != null && member.isDead()) {
                    logger.debug("GET komutu: Üye {} DEAD durumda, atlanıyor: id={}", memberId, messageId);
                }
            }

            if (aliveMembersToCheck.isEmpty()) {
                logger.warn("GET komutu: Mesaj için hiçbir ALIVE üye yok: id={}", messageId);
                return CommandHandler.NOT_FOUND;
            }

            logger.info("GET komutu: {} ALIVE üyede mesaj aranıyor: id={}", aliveMembersToCheck.size(), messageId);

            // 3. ALIVE üyelere sırayla gRPC Retrieve çağrısı yap
            // Crash olan üyeler otomatik olarak atlanır ve bir sonraki üyeye geçilir
            List<String> crashedMembers = new ArrayList<>();
            for (MemberInfo member : aliveMembersToCheck) {
                try {
                    logger.debug("GET komutu: Üye {}'den mesaj alınmaya çalışılıyor: id={}", 
                            member.getId(), messageId);
                    
                    String message = retrieveMessageFromMember(messageId, member);
                    if (message != null && !message.isEmpty()) {
                        String successLog = String.format(
                            "[GET SUCCESS] Mesaj üye %s (%s:%d)'den alındı | Mesaj ID: %d",
                            member.getId(), member.getHost(), member.getPort(), messageId
                        );
                        logger.info(successLog);
                        System.out.println(successLog);
                        
                        // Crash olan üyeler varsa logla
                        if (!crashedMembers.isEmpty()) {
                            String crashLog = String.format(
                                "[GET FALLBACK] Mesaj alınmadan önce %d üye crash oldu: %s",
                                crashedMembers.size(), String.join(", ", crashedMembers)
                            );
                            logger.warn(crashLog);
                            System.out.println(crashLog);
                        }
                        
                        return message;
                    } else {
                        logger.debug("GET komutu: Üye {}'den mesaj boş: id={}", member.getId(), messageId);
                        // Bir sonraki üyeyi dene
                    }
                } catch (Exception e) {
                    // Exception retrieveMessageFromMember içinde yakalanır ve üye DEAD olarak işaretlenir
                    crashedMembers.add(member.getId());
                    String fallbackLog = String.format(
                        "[GET FALLBACK] Üye %s (%s:%d) crash oldu, bir sonraki üyeye geçiliyor | Mesaj ID: %d",
                        member.getId(), member.getHost(), member.getPort(), messageId
                    );
                    logger.warn(fallbackLog);
                    System.out.println(fallbackLog);
                    // Otomatik olarak bir sonraki üyeye geçilir (loop devam eder)
                }
            }

            logger.warn("GET komutu: Mesaj hiçbir ALIVE üyede bulunamadı: id={}", messageId);
            return CommandHandler.NOT_FOUND;

        } catch (NumberFormatException e) {
            logger.error("GET komutu: Geçersiz ID formatı: {}", command.getId(), e);
            return "ERROR: Geçersiz ID formatı";
        } catch (Exception e) {
            logger.error("GET komutu: Beklenmeyen hata", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * ID'ye göre üye bulur (aktif ve ölü listede arar)
     * 
     * @param memberId Üye ID'si
     * @return Üye bilgisi, bulunamazsa null
     */
    private MemberInfo findMemberById(String memberId) {
        // Önce aktif listede ara
        MemberInfo member = activeMembers.stream()
                .filter(m -> m.getId().equals(memberId))
                .findFirst()
                .orElse(null);
        
        // Bulunamazsa ölü listede ara
        if (member == null) {
            member = deadMembers.stream()
                    .filter(m -> m.getId().equals(memberId))
                    .findFirst()
                    .orElse(null);
        }
        
        return member;
    }

    /**
     * Mesajı bir üyeden gRPC Retrieve çağrısı ile okur
     * Bağlantı hatası durumunda üyeyi DEAD olarak işaretler
     * 
     * @param messageId Mesaj ID'si
     * @param member Üye bilgisi
     * @return Mesaj içeriği, bulunamazsa null
     */
    private String retrieveMessageFromMember(Integer messageId, MemberInfo member) {
        ManagedChannel channel = null;
        try {
            // gRPC channel oluştur
            channel = ManagedChannelBuilder.forAddress(member.getHost(), member.getPort())
                    .usePlaintext()
                    .build();

            // Blocking stub oluştur
            StorageServiceGrpc.StorageServiceBlockingStub stub = StorageServiceGrpc.newBlockingStub(channel);

            // Retrieve RPC çağrısı
            com.sistem.proje.grpc.MessageId request = com.sistem.proje.grpc.MessageId.newBuilder()
                    .setId(messageId)
                    .build();

            StoredMessage result = stub.retrieve(request);

            // Başarılı ise üyeyi ALIVE olarak işaretle (recovery)
            if (member.isDead()) {
                markMemberAsAlive(member.getId());
            }

            // Boş text kontrolü
            String text = result.getText();
            if (text == null || text.isEmpty()) {
                return null;
            }

            return text;

        } catch (io.grpc.StatusRuntimeException e) {
            // gRPC bağlantı hatası - üyeyi DEAD olarak işaretle
            String errorReason = String.format("gRPC StatusRuntimeException: %s", e.getStatus().getCode());
            logger.error("gRPC Retrieve çağrısı bağlantı hatası: member={}, messageId={}, error={}", 
                    member, messageId, e.getStatus());
            markMemberAsDead(member.getId(), errorReason);
            throw e; // Üst seviyede yakalanması için fırlat
        } catch (Exception e) {
            // Diğer hatalar
            logger.error("gRPC Retrieve çağrısı hatası: member={}, messageId={}", member, messageId, e);
            // Bağlantı hatası gibi görünüyorsa DEAD olarak işaretle
            if (e instanceof java.net.ConnectException || 
                e instanceof java.io.IOException ||
                e.getCause() instanceof java.net.ConnectException) {
                String errorReason = e.getClass().getSimpleName() + ": " + e.getMessage();
                markMemberAsDead(member.getId(), errorReason);
            }
            throw e; // Üst seviyede yakalanması için fırlat
        } finally {
            if (channel != null) {
                shutdownChannel(channel);
            }
        }
    }

    /**
     * gRPC channel'ı güvenli şekilde kapatır
     * Sistemi kilitlemeden kısa bir timeout ile kapatma işlemini tamamlar
     * 
     * @param channel Kapatılacak channel
     */
    private void shutdownChannel(ManagedChannel channel) {
        if (channel == null || channel.isShutdown()) {
            return;
        }
        
        try {
            channel.shutdown();
            // Kısa timeout ile bekle (sistemi kilitlemez)
            if (!channel.awaitTermination(1, TimeUnit.SECONDS)) {
                // Timeout oldu, zorla kapat
                channel.shutdownNow();
                // Zorla kapatma için de kısa bekle
                try {
                    if (!channel.awaitTermination(1, TimeUnit.SECONDS)) {
                        logger.warn("gRPC channel kapatılamadı, timeout aşıldı");
                    }
                } catch (InterruptedException e) {
                    channel.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
            logger.debug("gRPC channel kapatılırken kesinti: {}", e.getMessage());
        } catch (Exception e) {
            logger.debug("gRPC channel kapatılırken hata: {}", e.getMessage());
        }
    }

    /**
     * Mesajı bir üyeye gRPC Store çağrısı ile kaydeder
     * Bağlantı hatası durumunda üyeyi DEAD olarak işaretler
     * 
     * @param messageId Mesaj ID'si
     * @param message Mesaj içeriği
     * @param member Üye bilgisi
     * @return Başarılı ise true
     */
    private boolean storeMessageToMember(Integer messageId, String message, MemberInfo member) {
        ManagedChannel channel = null;
        try {
            // gRPC channel oluştur
            channel = ManagedChannelBuilder.forAddress(member.getHost(), member.getPort())
                    .usePlaintext()
                    .build();

            // Blocking stub oluştur
            StorageServiceGrpc.StorageServiceBlockingStub stub = StorageServiceGrpc.newBlockingStub(channel);

            // Store RPC çağrısı
            StoredMessage request = StoredMessage.newBuilder()
                    .setId(messageId)
                    .setText(message)
                    .build();

            StoreResult result = stub.store(request);

            if (result.getSuccess()) {
                // Başarılı ise üyeyi ALIVE olarak işaretle (recovery)
                if (member.isDead()) {
                    markMemberAsAlive(member.getId());
                }
                return true;
            } else {
                return false;
            }

        } catch (io.grpc.StatusRuntimeException e) {
            // gRPC bağlantı hatası - üyeyi DEAD olarak işaretle
            String errorReason = String.format("gRPC StatusRuntimeException: %s", e.getStatus().getCode());
            logger.error("gRPC Store çağrısı bağlantı hatası: member={}, messageId={}, error={}", 
                    member, messageId, e.getStatus());
            markMemberAsDead(member.getId(), errorReason);
            return false;
        } catch (Exception e) {
            // Diğer hatalar
            logger.error("gRPC Store çağrısı hatası: member={}, messageId={}", member, messageId, e);
            // Bağlantı hatası gibi görünüyorsa DEAD olarak işaretle
            if (e instanceof java.net.ConnectException || 
                e instanceof java.io.IOException ||
                e.getCause() instanceof java.net.ConnectException) {
                String errorReason = e.getClass().getSimpleName() + ": " + e.getMessage();
                markMemberAsDead(member.getId(), errorReason);
            }
            return false;
        } finally {
            if (channel != null) {
                shutdownChannel(channel);
            }
        }
    }

    /**
     * Her client bağlantısı için mesaj işleme
     */
    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("Client {} komut aldı: {}", 
                            clientSocket.getRemoteSocketAddress(), line);
                    
                    try {
                        // Komutu parse et
                        Command command = commandParser.parse(line);
                        
                        // SET ve GET komutları özel işleme
                        String result;
                        if (command.getType() == com.sistem.proje.protocol.CommandType.SET) {
                            result = handleSetCommand((SetCommand) command);
                        } else if (command.getType() == com.sistem.proje.protocol.CommandType.GET) {
                            result = handleGetCommand((GetCommand) command);
                        } else {
                            // Diğer komutlar normal işleme
                            result = commandHandler.execute(command);
                        }
                        
                        // Sonucu client'a gönder
                        writer.println(result);
                        logger.debug("Client {} sonuç gönderildi: {}", 
                                clientSocket.getRemoteSocketAddress(), result);
                        
                    } catch (CommandParseException e) {
                        String errorMsg = "ERROR: " + e.getMessage();
                        writer.println(errorMsg);
                        logger.warn("Komut parse hatası: {}", e.getMessage());
                    } catch (Exception e) {
                        String errorMsg = "ERROR: " + e.getMessage();
                        writer.println(errorMsg);
                        logger.error("Komut çalıştırma hatası: ", e);
                    }
                }
            } catch (IOException e) {
                logger.info("Client bağlantısı kapatıldı: {}", 
                        clientSocket.getRemoteSocketAddress());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    logger.error("Socket kapatılırken hata: ", e);
                }
            }
        }
    }

    /**
     * Üye durumu
     */
    public enum MemberStatus {
        ALIVE,
        DEAD
    }

    /**
     * Üye bilgisi sınıfı
     */
    public static class MemberInfo {
        private final String id;
        private final String host;
        private final int port;
        private volatile MemberStatus status;

        public MemberInfo(String id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.status = MemberStatus.ALIVE;
        }

        public String getId() {
            return id;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public MemberStatus getStatus() {
            return status;
        }

        public void setStatus(MemberStatus status) {
            this.status = status;
        }

        public boolean isAlive() {
            return status == MemberStatus.ALIVE;
        }

        public boolean isDead() {
            return status == MemberStatus.DEAD;
        }

        @Override
        public String toString() {
            return String.format("MemberInfo{id='%s', host='%s', port=%d, status=%s}", 
                    id, host, port, status);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MemberInfo that = (MemberInfo) o;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    /**
     * Main metodu - Leader Node'u başlatır
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.error("Geçersiz port numarası: {}. Varsayılan port kullanılıyor: {}", 
                        args[0], DEFAULT_PORT);
            }
        }

        LeaderNode leader = new LeaderNode(port, IOMode.BUFFERED);
        
        // Üyeleri kaydet (bootstrap)
        registerDefaultMembers(leader);
        
        // Shutdown hook ekle
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown sinyali alındı...");
            leader.stop();
        }));

        leader.start();
    }

    /**
     * Varsayılan üyeleri kaydeder (bootstrap)
     * Üye bilgileri: member1 (localhost:9091), member2 (localhost:9092), member3 (localhost:9093)
     */
    private static void registerDefaultMembers(LeaderNode leader) {
        // Varsayılan üye listesi
        // Gerçek kullanımda bu bilgiler config dosyasından veya argümanlardan okunabilir
        leader.addMember("member1", "localhost", 9091);
        leader.addMember("member2", "localhost", 9092);
        leader.addMember("member3", "localhost", 9093);
        
        logger.info("Varsayılan üyeler kaydedildi: 3 üye");
    }
}

