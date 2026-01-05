# Abonelik Sistemi

TCP tabanlı dağıtık abonelik sistemi projesi.

## Proje Amacı

Bu proje, TCP protokolü üzerinden çalışan dağıtık bir abonelik sistemi geliştirmeyi amaçlamaktadır. Sistem, leader-follower mimarisi kullanarak yüksek erişilebilirlik ve ölçeklenebilirlik sağlar. Client uygulamaları, leader ve member node'lar üzerinden abonelik işlemlerini gerçekleştirebilir.

### Temel Özellikler

- TCP tabanlı iletişim protokolü
- Leader-follower (member) mimarisi
- gRPC servis desteği
- Dağıtık veri depolama
- Yüksek erişilebilirlik

## Kullanılan Teknolojiler

- **Java 11** - Programlama dili
- **Maven** - Proje yönetimi ve bağımlılık yönetimi
- **gRPC** - Yüksek performanslı RPC framework
- **SLF4J & Logback** - Logging framework
- **Gson** - JSON işleme
- **JUnit 5** - Test framework

## Mimari Açıklama

### Genel Mimari

Sistem, **leader-follower (member)** mimarisini kullanarak dağıtık bir mesaj saklama sistemi sunar. Üç ana bileşenden oluşur:

#### 1. Client
- TCP protokolü üzerinden text tabanlı komutlar gönderir
- Komutlar: `SET <id> <message>` ve `GET <id>`
- Leader node'a bağlanır ve isteklerini iletir

#### 2. Leader Node
- Sistemin koordinasyon merkezidir
- TCP server olarak client isteklerini kabul eder (varsayılan port: 8080)
- Komutları parse eder ve işler
- Member node'ları yönetir ve yük dağıtımını kontrol eder
- Mesaj-üye eşleşmelerini memory'de tutar (`Map<Integer, List<String>>`)
- Periyodik istatistik yazdırır (her 10 saniyede bir)

#### 3. Member Node
- gRPC server olarak çalışır (varsayılan port: 9090)
- Leader node'dan gelen gRPC Store/Retrieve çağrılarını işler
- Mesajları disk'te `messages/` klasöründe saklar (dosya formatı: `<id>.msg`)
- Periyodik olarak diskteki mesaj sayısını console'a yazdırır

### İletişim Protokolleri

#### Client → Leader: TCP + Text Tabanlı

**Protokol**: TCP socket üzerinden plain text
- **Port**: 8080 (varsayılan)
- **Format**: 
  - `SET <id> <message>` - Mesaj kaydetme
  - `GET <id>` - Mesaj okuma
- **Yanıtlar**: 
  - `OK` - Başarılı
  - `NOT_FOUND` - Mesaj bulunamadı
  - `ERROR: <mesaj>` - Hata durumu

**Avantajlar**:
- Basit ve anlaşılır protokol
- Herhangi bir TCP client ile test edilebilir
- Debug kolaylığı

#### Leader → Member: gRPC + Protobuf

**Protokol**: gRPC (HTTP/2 üzerinde)
- **Port**: 9090+ (her member farklı port)
- **Format**: Protobuf serialization
- **RPC Metodları**:
  - `Store(StoredMessage) → StoreResult` - Mesaj kaydetme
  - `Retrieve(MessageId) → StoredMessage` - Mesaj okuma

**Avantajlar**:
- Yüksek performans (binary serialization)
- Type-safe mesaj yapısı
- Otomatik code generation
- Cross-language desteği

**Protobuf Mesajları**:
```protobuf
message StoredMessage {
  int32 id = 1;
  string text = 2;
}

message MessageId {
  int32 id = 1;
}

message StoreResult {
  bool success = 1;
}
```

### Hata Toleransı Mantığı

#### Tolerance Mekanizması

**Amaç**: Her mesajın `N` kopyasını farklı üyelerde saklamak

**Çalışma Prensibi**:
1. `tolerance.conf` dosyasından tolerance değeri okunur (1-7 arası)
2. SET işleminde:
   - Lider mesajı kendi diskine kaydeder
   - Tolerance kadar üye seçilir (load balancing stratejisine göre)
   - Seçilen üyelere gRPC Store çağrısı yapılır
   - Başarılı üyeler `messageToMembers` map'ine kaydedilir
3. GET işleminde:
   - Önce lider diskinde kontrol edilir
   - Yoksa `messageToMembers` map'inden üye listesi alınır
   - Üyelere sırayla gRPC Retrieve çağrısı yapılır
   - İlk başarılı cevap döndürülür

**Örnek (Tolerance=2)**:
- Mesaj ID 100 → Lider + Member1 + Member2'de saklanır
- Member1 crash olursa → Member2'den okunur
- Her iki member crash olursa → Lider diskinden okunur

#### Üye Durum Yönetimi

**İki Liste Yapısı**:
- `activeMembers`: Erişilebilir üyeler
- `deadMembers`: Crash olan üyeler

**Durum Geçişleri**:
- **ALIVE → DEAD**: gRPC çağrısı sırasında exception alınırsa
  - Üye `activeMembers`'tan çıkarılır
  - `deadMembers`'a eklenir
  - Log: `"Member X marked as DEAD"`
- **DEAD → ALIVE**: Başarılı gRPC çağrısı yapılırsa (recovery)
  - Üye `deadMembers`'tan çıkarılır
  - `activeMembers`'a eklenir
  - Log: `"Member X marked as ALIVE"`

### Crash Sonrası Recovery

#### Otomatik Fallback Mekanizması

**GET İşlemi Sırasında**:
1. Lider diskinde kontrol edilir
2. Yoksa `messageToMembers` map'inden üye listesi alınır
3. Sadece `activeMembers` listesindeki üyelere çağrı yapılır
4. Bir üye crash olursa:
   - Exception yakalanır
   - Üye DEAD olarak işaretlenir
   - Otomatik olarak bir sonraki üyeye geçilir
   - Log: `"[GET FALLBACK] Üye X crash oldu, bir sonraki üyeye geçiliyor"`
5. Hayatta kalan son üyeden mesaj alınabilirse başarılı döner

**SET İşlemi Sırasında**:
1. Lider diskine kaydedilir
2. Tolerance kadar üye seçilir (sadece `activeMembers`'tan)
3. Seçilen üyelere gRPC Store çağrısı yapılır
4. Bir üye crash olursa:
   - Exception yakalanır
   - Üye DEAD olarak işaretlenir
   - Diğer üyelere devam edilir
   - Log: `"[SET CRASH] Mesaj kaydedilirken X üye crash oldu"`
5. En az bir üye başarılıysa işlem devam eder

#### Recovery Mekanizması

**Otomatik Recovery**:
- DEAD olarak işaretlenmiş bir üyeye başarılı gRPC çağrısı yapılırsa
- Üye otomatik olarak ALIVE olarak işaretlenir
- `deadMembers`'tan `activeMembers`'a taşınır
- Manuel müdahale gerekmez

**Örnek Senaryo**:
1. Member1 crash olur → DEAD olarak işaretlenir
2. Member1 yeniden başlatılır
3. Sonraki SET/GET işleminde Member1'e çağrı yapılır
4. Başarılı olursa → Otomatik olarak ALIVE olarak işaretlenir
5. Sistem normal çalışmaya devam eder

#### Load Balancing ve Crash Toleransı

**Üye Seçimi**:
- Sadece `activeMembers` listesinden seçim yapılır
- DEAD üyeler otomatik olarak atlanır
- Hash-based veya Round-robin stratejisi kullanılır

**Crash Sonrası Dağılım**:
- Üye sayısı azalırsa, kalan aktif üyelere yük dağıtılır
- Tolerance değeri, aktif üye sayısından fazla olamaz
- Sistem minimum 1 aktif üye ile çalışmaya devam eder

### İşleyiş Akışı

#### SET İşlemi
```
1. Client → Leader: "SET 123 Merhaba"
2. Leader → Kendi diski: Mesajı kaydet
3. Leader → tolerance.conf okur (örn: TOLERANCE=2)
4. Leader → 2 üye seçer (load balancing stratejisine göre)
5. Leader → Üyelere gRPC Store çağrısı yapar
6. Leader → Başarılı üyeleri messageToMembers map'ine kaydeder
7. Leader → Client: "OK" veya "ERROR"
```

#### GET İşlemi
```
1. Client → Leader: "GET 123"
2. Leader → Kendi diskinde kontrol et
   ├─ Varsa → Mesajı döndür
   └─ Yoksa → 3. adıma geç
3. Leader → messageToMembers map'inden üye listesini al
4. Leader → Üyelere sırayla gRPC Retrieve çağrısı yapar
5. Leader → İlk başarılı cevabı client'a döndürür
```

### Veri Depolama

- **Lider**: `messages/` klasöründe mesajları saklar
- **Üyeler**: Kendi `messages/` klasöründe mesajları saklar
- **Mesaj Takibi**: Leader'da `messageToMembers` map'i ile hangi mesajın hangi üyelerde olduğu takip edilir

### Paket Yapısı

```
src/main/java/com/sistem/proje/
├── client/          # Client uygulaması implementasyonu
├── leader/          # Leader node implementasyonu
├── member/          # Member node implementasyonu
├── config/          # Sistem konfigürasyon yönetimi
├── network/         # TCP ağ yönetimi ve iletişim
├── storage/         # Veri depolama ve yönetim
├── protocol/        # TCP protokol işleme
└── grpc/            # gRPC servis implementasyonları
```

## Gereksinimler

- Java 11 veya üzeri
- Maven 3.6 veya üzeri

## Kurulum

Projeyi klonladıktan sonra, bağımlılıkları yüklemek için:

```bash
mvn clean install
```

## Çalıştırma Talimatları

### ⭐ IDE ile Çalıştırma (ÖNERİLEN)

**En kolay ve garantili yöntem! Tüm dependency'ler otomatik yüklenir.**

#### VS Code:
1. Projeyi VS Code'da açın
2. Java Extension Pack yüklü olduğundan emin olun
3. Projeyi derleyin (Ctrl+Shift+B veya Command Palette → "Java: Build Project")
4. `src/main/java/com/sistem/proje/leader/LeaderNode.java` dosyasını açın
5. `main` metodunun üstündeki **"Run"** butonuna tıklayın
6. Aynı şekilde `MemberNode.java` için de yapın (3 farklı terminal/port: 9091, 9092, 9093)

#### IntelliJ IDEA:
1. Projeyi IntelliJ'de açın (File → Open → `pom.xml` seçin)
2. Maven dependency'lerinin indirilmesini bekleyin (sağ altta progress bar)
3. `LeaderNode.java` dosyasını açın
4. `main` metoduna sağ tıklayın → **"Run 'LeaderNode.main()'"**
5. Aynı şekilde `MemberNode.java` için de yapın

#### Eclipse:
1. Projeyi Eclipse'de açın (File → Import → Maven → Existing Maven Projects)
2. Projeyi derleyin (Project → Build Project)
3. `LeaderNode.java` → Sağ tık → **Run As → Java Application**
4. Aynı şekilde `MemberNode.java` için de yapın

### Maven ile Çalıştırma (Maven Kuruluysa)

```bash
# Projeyi derle
mvn clean compile

# Leader Node
mvn exec:java -Dexec.mainClass="com.sistem.proje.leader.LeaderNode"

# Member Node (3 farklı terminal)
mvn exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9091"
mvn exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9092"
mvn exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9093"
```

### Test Etme

TCP client ile test edin:

**PowerShell ile:**
```powershell
$client = New-Object System.Net.Sockets.TcpClient("localhost", 8080)
$stream = $client.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$reader = New-Object System.IO.StreamReader($stream)

# SET komutu
$writer.WriteLine("SET 1 Merhaba Dünya")
$writer.Flush()
$response = $reader.ReadLine()
Write-Host "SET: $response"

# GET komutu
$writer.WriteLine("GET 1")
$writer.Flush()
$response = $reader.ReadLine()
Write-Host "GET: $response"

$writer.Close()
$reader.Close()
$client.Close()
```

**Detaylı test rehberi için:** `TEST_REHBERI.md` dosyasına bakın.

## Hata Toleransı Mekanizması

### Üye Durum Yönetimi

Sistem, üyelerin durumunu **ALIVE** ve **DEAD** olmak üzere iki durumda takip eder:

- **ALIVE**: Üye aktif ve erişilebilir durumda
- **DEAD**: Üye crash olmuş veya erişilemiyor

### Otomatik Crash Tespiti

gRPC çağrıları sırasında oluşan exception'lar yakalanır ve üye otomatik olarak DEAD olarak işaretlenir:

- **StatusRuntimeException**: gRPC bağlantı hataları
- **ConnectException**: Network bağlantı hataları
- **IOException**: Genel IO hataları

### Crash İşleme Akışı

#### SET İşlemi Sırasında Crash
```
1. Üyeye gRPC Store çağrısı yapılır
2. Exception oluşursa → Üye DEAD olarak işaretlenir
3. Log yazdırılır: [CRASH] Üye öldü: member1 (host:port) | Neden: ...
4. Diğer üyelere devam edilir
5. Başarılı üyeler kaydedilir
```

#### GET İşlemi Sırasında Crash
```
1. Üyeden gRPC Retrieve çağrısı yapılır
2. Exception oluşursa → Üye DEAD olarak işaretlenir
3. Log yazdırılır: [GET FALLBACK] Üye crash oldu, bir sonraki üyeye geçiliyor
4. Otomatik olarak bir sonraki üyeye geçilir
5. Hayatta kalan son üyeden mesaj alınabilirse döndürülür
```

### Recovery Mekanizması

- Başarılı gRPC çağrısı yapıldığında üye otomatik olarak ALIVE olarak işaretlenir
- DEAD üyeler üye seçiminden otomatik olarak çıkarılır
- Sadece ALIVE üyeler işlemler için kullanılır

### Tolerance Mekanizması

- `tolerance.conf` dosyasından tolerance değeri okunur (1-7 arası)
- SET işleminde tolerance kadar üyeye mesaj kopyalanır
- GET işleminde tolerance kadar üyeden mesaj okunabilir
- Bir üye crash olursa, diğer tolerance-1 üyeden mesaj alınabilir

## Load Balancing Yaklaşımı

Sistem iki farklı load balancing stratejisi destekler:

### 1. Hash-Based (Varsayılan)

**Algoritma**: `message_id % member_count`

**Özellikler**:
- Deterministik seçim: Aynı mesaj ID'si her zaman aynı üyeleri seçer
- Öngörülebilir dağılım
- Tutarlılık: Aynı mesaj aynı üyelere yazılır

**Örnek**:
```
Üyeler: [member1, member2, member3]
SET 100 → 100 % 3 = 1 → [member2, member3]
SET 101 → 101 % 3 = 2 → [member3, member1]
SET 102 → 102 % 3 = 0 → [member1, member2]
```

### 2. Round-Robin

**Algoritma**: Sırayla üye seçimi (circular)

**Özellikler**:
- Daha dengeli yük dağılımı
- Üyeler sırayla kullanılır
- Thread-safe counter ile çalışır

**Örnek**:
```
Üyeler: [member1, member2, member3]
SET 100 → [member1, member2] (counter: 0)
SET 101 → [member3, member1] (counter: 2)
SET 102 → [member2, member3] (counter: 1)
```

### Üye Seçimi

- **Sadece ALIVE üyeler seçilir**: DEAD üyeler otomatik olarak atlanır
- **Tolerance kadar seçim**: `tolerance.conf` dosyasında belirtilen kadar üye seçilir
- **Circular seçim**: Tolerance kadar üye seçilirken circular pattern kullanılır

### Strateji Seçimi

```java
// Hash-based (varsayılan)
LeaderNode leader = new LeaderNode();

// Round-robin
LeaderNode leader = new LeaderNode(8080, IOMode.BUFFERED, LoadBalancingStrategy.ROUND_ROBIN);
```

### Back-up İçin Üye Seçme Algoritmasının Özgünlüğü

Sistemin back-up için üye seçme algoritması, geleneksel load balancing yaklaşımlarından farklı olarak **fault tolerance** ve **veri tutarlılığı** odaklı tasarlanmıştır. Özgün yönleri şunlardır:

1. **Dinamik ALIVE Üye Filtreleme**: Algoritma, sadece aktif (ALIVE) üyeleri seçerek crash olan üyeleri otomatik olarak atlar. Bu sayede sistem, üye crash'lerinden etkilenmeden çalışmaya devam eder.

2. **Tolerance-Based Replication**: `tolerance.conf` dosyasından okunan tolerance değeri kadar üye seçilir. Bu yaklaşım, N-replication stratejisini esnek bir şekilde uygular ve tolerance değeri 1-7 arasında ayarlanabilir.

3. **Deterministik Hash-Based Seçim**: Hash-based stratejide, aynı mesaj ID'si her zaman aynı üye setini seçer (`message_id % member_count`). Bu sayede veri tutarlılığı sağlanır ve aynı mesaj her zaman aynı üyelere yazılır.

4. **Circular Pattern ile Yük Dengesi**: Seçilen tolerance kadar üye, circular pattern kullanılarak seçilir. Örneğin, tolerance=2 ve 3 üye varsa, ilk üyeden başlayarak 2 üye seçilir (circular olarak).

5. **Otomatik Crash Recovery**: Başarılı gRPC çağrısı yapıldığında, önceden DEAD olarak işaretlenmiş üyeler otomatik olarak ALIVE olarak işaretlenir. Bu sayede üye recovery durumları otomatik olarak tespit edilir.

6. **Load Balancing ile Fault Tolerance Birleşimi**: Sistem, hem yük dağıtımı (load balancing) hem de hata toleransı (fault tolerance) mekanizmalarını aynı anda kullanır. Bu, performans ve güvenilirlik arasında optimal denge sağlar.

Bu yaklaşım, geleneksel master-slave replikasyon modellerinden farklı olarak, her mesajın tolerance kadar üyede saklanmasını garanti eder ve sistemin üye crash'lerinden etkilenmeden çalışmasını sağlar.

## Crash Senaryosu Testleri

### Test Senaryoları

#### Senaryo 1: SET İşlemi Sırasında Üye Crash

Bu senaryo, bir üye node'un SET işlemi sırasında crash olması durumunda sistemin nasıl davrandığını test eder.

**Adım Adım Açıklama**:

1. **Leader Node'u Başlat**: Leader node TCP port 8080'de çalışır
2. **Member Node'ları Başlat**: 3 member node'u farklı portlarda başlat (9091, 9092, 9093)
3. **Üyeleri Leader'a Ekle**: Üyeler runtime'da leader'a eklenir (kod içinde)
4. **SET Komutu Gönder**: `SET 100 "Test"` komutu gönderilir (tolerance=2 olduğu için 2 üyeye kaydedilir)
5. **Bir Member Node'u Durdur**: Örneğin member2 (port 9092) durdurulur
6. **SET Komutu Tekrar Gönder**: Aynı veya farklı bir mesaj için SET komutu tekrar gönderilir

**Beklenen Sonuç**:
- İlk SET: 2 üyeye başarıyla kaydedilir
- İkinci SET: member2 crash olduğu için DEAD olarak işaretlenir
- Log: `[CRASH] Üye öldü: member2 (localhost:9092) | Neden: ...`
- Sadece ALIVE üyeler seçilir (member1, member3)

**Terminal Komutları**:

```bash
# Terminal 1: Leader Node'u başlat
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.leader.LeaderNode" -Dexec.args="8080"

# Terminal 2: Member Node 1 (port 9091)
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9091"

# Terminal 3: Member Node 2 (port 9092)
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9092"

# Terminal 4: Member Node 3 (port 9093)
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9093"

# Terminal 5: TCP Client ile test
# Windows için PowerShell kullanılabilir veya telnet/nc

# Önce tolerance.conf dosyasını oluştur (proje root'unda)
echo "TOLERANCE=2" > tolerance.conf

# SET komutu gönder (nc veya telnet ile)
# Eğer nc (netcat) yüklü değilse, Java TCP Client kullanılabilir
echo "SET 100 Test mesajı" | nc localhost 8080

# Member Node 2'yi durdur (Terminal 3'te Ctrl+C)

# SET komutu tekrar gönder
echo "SET 101 Yeni mesaj" | nc localhost 8080

# Leader Node log'larında şu mesajlar görülmeli:
# [CRASH] Üye öldü: member2 (localhost:9092) | Neden: gRPC StatusRuntimeException: UNAVAILABLE
# [SET CRASH] Mesaj kaydedilirken 1 üye crash oldu: member2 | Mesaj ID: 101
```

**Not**: Windows'ta `nc` komutu yoksa, Java ile basit bir TCP client yazılabilir veya telnet kullanılabilir:

```bash
# Telnet ile (Windows'ta varsayılan olarak yüklü değil, etkinleştirilmesi gerekebilir)
telnet localhost 8080
# Bağlantı kurulduktan sonra:
SET 100 Test mesajı
GET 100
```

#### Senaryo 2: GET İşlemi Sırasında Üye Crash

Bu senaryo, bir üye node'un GET işlemi sırasında crash olması durumunda sistemin nasıl otomatik olarak bir sonraki üyeye geçtiğini test eder.

**Adım Adım Açıklama**:

1. **Leader Node'u Başlat**: Leader node TCP port 8080'de çalışır
2. **Member Node'ları Başlat**: 3 member node'u farklı portlarda başlat (9091, 9092, 9093)
3. **Mesajı Kaydet**: `SET 123 "Test Mesajı"` komutu ile mesajı kaydet (tolerance=2 olduğu için 2 üyeye kaydedilir)
4. **Bir Member Node'u Durdur**: Mesajın saklandığı üyelerden birini durdur (örneğin member1 - port 9091)
5. **GET Komutu Gönder**: `GET 123` komutu gönderilir

**Beklenen Sonuç**:
- İlk üyeye Retrieve çağrısı yapılır (member1)
- Üye crash olduğu için DEAD olarak işaretlenir
- Log: `[GET FALLBACK] Üye member1 (localhost:9091) crash oldu, bir sonraki üyeye geçiliyor | Mesaj ID: 123`
- Log: `[CRASH] Üye öldü: member1 (localhost:9091) | Neden: gRPC StatusRuntimeException: UNAVAILABLE`
- Otomatik olarak bir sonraki üyeye geçilir (member2)
- Başarılı üyeden mesaj alınır ve döndürülür
- Log: `[GET SUCCESS] Mesaj üye member2 (localhost:9092)'den alındı | Mesaj ID: 123`
- Log: `[GET FALLBACK] Mesaj alınmadan önce 1 üye crash oldu: member1`

**Terminal Komutları**:

```bash
# Terminal 1: Leader Node'u başlat
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.leader.LeaderNode" -Dexec.args="8080"

# Terminal 2: Member Node 1 (port 9091)
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9091"

# Terminal 3: Member Node 2 (port 9092)
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9092"

# Terminal 4: Member Node 3 (port 9093)
cd C:\Users\nuran\Desktop\Sistem-Proje
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9093"

# Terminal 5: TCP Client ile test

# Önce tolerance.conf dosyasını oluştur (proje root'unda)
echo "TOLERANCE=2" > tolerance.conf

# SET komutu ile mesajı kaydet
echo "SET 123 Test Mesajı" | nc localhost 8080
# Beklenen çıktı: OK

# Member Node 1'i durdur (Terminal 2'de Ctrl+C)

# GET komutu gönder
echo "GET 123" | nc localhost 8080
# Beklenen çıktı: Test Mesajı

# Leader Node log'larında şu mesajlar görülmeli:
# [GET FALLBACK] Üye member1 (localhost:9091) crash oldu, bir sonraki üyeye geçiliyor | Mesaj ID: 123
# [CRASH] Üye öldü: member1 (localhost:9091) | Neden: gRPC StatusRuntimeException: UNAVAILABLE
# [GET SUCCESS] Mesaj üye member2 (localhost:9092)'den alındı | Mesaj ID: 123
# [GET FALLBACK] Mesaj alınmadan önce 1 üye crash oldu: member1
```

**Windows PowerShell Alternatifi**:

Windows'ta `nc` komutu yoksa, PowerShell ile TCP bağlantısı kurulabilir:

```powershell
# PowerShell ile TCP bağlantısı
$tcpClient = New-Object System.Net.Sockets.TcpClient("localhost", 8080)
$stream = $tcpClient.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$reader = New-Object System.IO.StreamReader($stream)

# SET komutu gönder
$writer.WriteLine("SET 123 Test Mesajı")
$writer.Flush()
$reader.ReadLine()

# GET komutu gönder
$writer.WriteLine("GET 123")
$writer.Flush()
$reader.ReadLine()

# Bağlantıyı kapat
$writer.Close()
$reader.Close()
$tcpClient.Close()
```

**Beklenen Log Çıktıları**:

Leader Node konsol çıktısında aşağıdaki log'lar görülmelidir:

```
[SET SUCCESS] Mesaj üye member1 (localhost:9091)'ye kaydedildi | Mesaj ID: 123
[SET SUCCESS] Mesaj üye member2 (localhost:9092)'ye kaydedildi | Mesaj ID: 123
[GET FALLBACK] Üye member1 (localhost:9091) crash oldu, bir sonraki üyeye geçiliyor | Mesaj ID: 123
[CRASH] Üye öldü: member1 (localhost:9091) | Neden: gRPC StatusRuntimeException: UNAVAILABLE
[GET SUCCESS] Mesaj üye member2 (localhost:9092)'den alındı | Mesaj ID: 123
[GET FALLBACK] Mesaj alınmadan önce 1 üye crash oldu: member1
```

#### Senaryo 3: Tüm Üyeler Crash

**Adımlar**:
1. Leader node'u başlat
2. 2 member node başlat
3. `SET 200 "Test"` ile mesajı kaydet
4. Tüm member node'ları durdur
5. `GET 200` komutu gönder

**Beklenen Sonuç**:
- Lider diskinde mesaj yoksa
- Tüm üyeler DEAD olarak işaretlenir
- Log: `[GET FALLBACK] Üye crash oldu, bir sonraki üyeye geçiliyor` (her üye için)
- Sonuç: `NOT_FOUND`

#### Senaryo 4: Üye Recovery

**Adımlar**:
1. Leader node'u başlat
2. Member node başlat
3. `SET 300 "Test"` ile mesajı kaydet
4. Member node'u durdur
5. `GET 300` komutu gönder (member DEAD olarak işaretlenir)
6. Member node'u yeniden başlat
7. `GET 300` komutu tekrar gönder

**Beklenen Sonuç**:
- İlk GET: Member DEAD, NOT_FOUND
- İkinci GET: Member'a başarılı çağrı yapılır
- Üye otomatik olarak ALIVE olarak işaretlenir
- Log: `Üye ALIVE olarak işaretlendi: member1`
- Mesaj başarıyla döndürülür

---

## Test Senaryosu 1: Tolerance=2, Crash

**Amaç**: Tolerance=2 ile 4 üye arasında crash senaryosunu test etmek.

### Terminal Komutları

```bash
# tolerance.conf dosyasını oluştur
echo "TOLERANCE=2" > tolerance.conf

# Terminal 1: Leader Node
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.leader.LeaderNode" -Dexec.args="8080"

# Terminal 2-5: Member Nodes (port 9091, 9092, 9093, 9094)
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9091"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9092"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9093"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9094"

# Terminal 6: TCP Client (PowerShell)
$tcpClient = New-Object System.Net.Sockets.TcpClient("localhost", 8080)
$stream = $tcpClient.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$reader = New-Object System.IO.StreamReader($stream)

# SET komutları gönder
$writer.WriteLine("SET 100 Test1")
$writer.Flush()
$reader.ReadLine()

$writer.WriteLine("SET 101 Test2")
$writer.Flush()
$reader.ReadLine()

# Member 2'yi durdur (Terminal 3'te Ctrl+C)

# SET komutu tekrar gönder
$writer.WriteLine("SET 102 Test3")
$writer.Flush()
$reader.ReadLine()

# GET komutu gönder (member2'de olan mesaj)
$writer.WriteLine("GET 100")
$writer.Flush()
$reader.ReadLine()
```

### Beklenen Log Çıktıları

**Leader Node Başlangıç:**
```
Member registered: member1 (localhost:9091)
Member registered: member2 (localhost:9092)
Member registered: member3 (localhost:9093)
Member registered: member4 (localhost:9094)
Leader Node başlatıldı. Port: 8080
```

**SET 100 (Member2 crash öncesi):**
```
[SET SUCCESS] Mesaj üye member1 (localhost:9091)'ye kaydedildi | Mesaj ID: 100
[SET SUCCESS] Mesaj üye member2 (localhost:9092)'ye kaydedildi | Mesaj ID: 100
```

**SET 102 (Member2 crash sonrası):**
```
Member member2 marked as DEAD
[SET CRASH] Mesaj kaydedilirken 1 üye crash oldu: member2 | Mesaj ID: 102
[SET SUCCESS] Mesaj üye member1 (localhost:9091)'ye kaydedildi | Mesaj ID: 102
[SET SUCCESS] Mesaj üye member3 (localhost:9093)'ye kaydedildi | Mesaj ID: 102
```

**GET 100 (Member2 crash sonrası):**
```
[GET FALLBACK] Üye member2 (localhost:9092) crash oldu, bir sonraki üyeye geçiliyor | Mesaj ID: 100
[GET SUCCESS] Mesaj üye member1 (localhost:9091)'den alındı | Mesaj ID: 100
```

---

## Test Senaryosu 2: Tolerance=3, Crash

**Amaç**: Tolerance=3 ile 6 üye arasında çoklu crash senaryosunu test etmek.

### Terminal Komutları

```bash
# tolerance.conf dosyasını oluştur
echo "TOLERANCE=3" > tolerance.conf

# Terminal 1: Leader Node
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.leader.LeaderNode" -Dexec.args="8080"

# Terminal 2-7: Member Nodes (port 9091-9096)
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9091"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9092"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9093"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9094"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9095"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9096"

# Terminal 8: TCP Client
$tcpClient = New-Object System.Net.Sockets.TcpClient("localhost", 8080)
$stream = $tcpClient.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$reader = New-Object System.IO.StreamReader($stream)

# SET komutları gönder
$writer.WriteLine("SET 200 Message1")
$writer.Flush()
$reader.ReadLine()

$writer.WriteLine("SET 201 Message2")
$writer.Flush()
$reader.ReadLine()

# Member 2 ve 4'ü durdur (Terminal 3 ve 5'te Ctrl+C)

# SET komutu tekrar gönder
$writer.WriteLine("SET 202 Message3")
$writer.Flush()
$reader.ReadLine()

# GET komutu gönder
$writer.WriteLine("GET 200")
$writer.Flush()
$reader.ReadLine()
```

### Beklenen Log Çıktıları

**SET 200 (Crash öncesi):**
```
[SET SUCCESS] Mesaj üye member1 (localhost:9091)'ye kaydedildi | Mesaj ID: 200
[SET SUCCESS] Mesaj üye member2 (localhost:9092)'ye kaydedildi | Mesaj ID: 200
[SET SUCCESS] Mesaj üye member3 (localhost:9093)'ye kaydedildi | Mesaj ID: 200
```

**SET 202 (2 üye crash sonrası):**
```
Member member2 marked as DEAD
Member member4 marked as DEAD
[SET CRASH] Mesaj kaydedilirken 2 üye crash oldu: member2, member4 | Mesaj ID: 202
[SET SUCCESS] Mesaj üye member1 (localhost:9091)'ye kaydedildi | Mesaj ID: 202
[SET SUCCESS] Mesaj üye member3 (localhost:9093)'ye kaydedildi | Mesaj ID: 202
[SET SUCCESS] Mesaj üye member5 (localhost:9095)'ye kaydedildi | Mesaj ID: 202
```

**GET 200 (2 üye crash sonrası):**
```
[GET FALLBACK] Üye member2 (localhost:9092) crash oldu, bir sonraki üyeye geçiliyor | Mesaj ID: 200
[GET SUCCESS] Mesaj üye member1 (localhost:9091)'den alındı | Mesaj ID: 200
[GET FALLBACK] Mesaj alınmadan önce 1 üye crash oldu: member2
```

---

## Load Balancing Testi

**Amaç**: Hash-based ve Round-robin load balancing stratejilerinin dağılımını test etmek.

### Terminal Komutları

```bash
# tolerance.conf dosyasını oluştur
echo "TOLERANCE=2" > tolerance.conf

# Terminal 1: Leader Node (Hash-based - varsayılan)
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.leader.LeaderNode" -Dexec.args="8080"

# Terminal 2-4: Member Nodes
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9091"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9092"
mvn compile exec:java -Dexec.mainClass="com.sistem.proje.member.MemberNode" -Dexec.args="9093"

# Terminal 5: Script ile 100 SET komutu gönder
for ($i=1; $i -le 100; $i++) {
    $tcpClient = New-Object System.Net.Sockets.TcpClient("localhost", 8080)
    $stream = $tcpClient.GetStream()
    $writer = New-Object System.IO.StreamWriter($stream)
    $reader = New-Object System.IO.StreamReader($stream)
    $writer.WriteLine("SET $i Message$i")
    $writer.Flush()
    $reader.ReadLine()
    $writer.Close()
    $reader.Close()
    $tcpClient.Close()
}

# 10 saniye bekle (periyodik istatistikler için)
Start-Sleep -Seconds 10
```

### Beklenen Log Çıktıları

**Periyodik İstatistikler (Her 10 saniyede):**
```
[STATS]
Total messages: 100
Member member1: 33
Member member2: 34
Member member3: 33
```

**Hash-based Strateji Özellikleri:**
- Aynı mesaj ID'si her zaman aynı üyelere yazılır
- Deterministik dağılım
- Mesaj ID 100 → member1 ve member2'ye yazılır (100 % 3 = 1)

**Round-robin Stratejisi Testi:**

LeaderNode'u Round-robin ile başlatmak için kod değişikliği gerekir:
```java
LeaderNode leader = new LeaderNode(8080, IOMode.BUFFERED, LoadBalancingStrategy.ROUND_ROBIN);
```

**Round-robin Beklenen Dağılım:**
```
[STATS]
Total messages: 100
Member member1: 34
Member member2: 33
Member member3: 33
```

**Doğrulama:**
- Hash-based: Mesaj ID'leri deterministik üyelere yazılır
- Round-robin: Üyeler sırayla kullanılır, daha dengeli dağılım

### Log Çıktıları

Crash senaryolarında aşağıdaki log formatları gözlemlenir:

```
[CRASH] Üye öldü: member1 (localhost:9091) | Neden: gRPC StatusRuntimeException: UNAVAILABLE
[SET SUCCESS] Mesaj üye member2 (localhost:9092)'ye kaydedildi | Mesaj ID: 123
[SET CRASH] Mesaj kaydedilirken 1 üye crash oldu: member1 | Mesaj ID: 123
[GET FALLBACK] Üye member1 (localhost:9091) crash oldu, bir sonraki üyeye geçiliyor | Mesaj ID: 123
[GET SUCCESS] Mesaj üye member2 (localhost:9092)'den alındı | Mesaj ID: 123
[GET FALLBACK] Mesaj alınmadan önce 1 üye crash oldu: member1
```

### Test Komutları

```bash
# 1. Leader node'u başlat
java -cp target/classes com.sistem.proje.leader.LeaderNode 8080

# 2. Member node'ları başlat (farklı terminal'lerde)
java -cp target/classes com.sistem.proje.member.MemberNode 9091
java -cp target/classes com.sistem.proje.member.MemberNode 9092
java -cp target/classes com.sistem.proje.member.MemberNode 9093

# 3. TCP client ile test (farklı terminal'de)
echo "SET 100 Test mesajı" | nc localhost 8080
echo "GET 100" | nc localhost 8080
```

## Test Senaryoları

Aşağıdaki test senaryoları proje geliştirme sürecinde uygulanacaktır:

### Birim Testleri
- [ ] Client bağlantı testleri
- [ ] Leader node başlatma ve durdurma testleri
- [ ] Member node başlatma ve durdurma testleri
- [ ] Protokol işleme testleri
- [ ] Veri depolama testleri
- [ ] CommandParser testleri
- [ ] Load balancing algoritma testleri

### Entegrasyon Testleri
- [ ] Client-Leader iletişim testleri
- [ ] Leader-Member gRPC iletişim testleri
- [ ] Yük dağıtımı testleri
- [ ] Failover senaryoları testleri
- [ ] Tolerance mekanizması testleri

### Sistem Testleri
- [ ] Çoklu client senaryoları
- [ ] Yüksek yük testleri
- [ ] Ağ kesintisi senaryoları
- [ ] Crash recovery testleri
- [ ] Load balancing performans testleri

## Proje Yapısı

Detaylı proje yapısı için yukarıdaki "Paket Yapısı" bölümüne bakınız.

## Geliştirme

### Kod Standartları
- Java kod standartlarına uygun yazım
- Anlamlı değişken ve metod isimleri
- Yorum satırları önemli noktalarda

### Katkıda Bulunma
1. Yeni özellikler için branch oluşturun
2. Değişikliklerinizi commit edin
3. Pull request oluşturun

## Lisans

TODO: Lisans bilgisi eklenecek
