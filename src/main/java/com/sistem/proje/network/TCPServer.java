package com.sistem.proje.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Basit TCP Server - Text tabanlı mesaj alır
 */
public class TCPServer {
    private static final Logger logger = LoggerFactory.getLogger(TCPServer.class);
    private static final int DEFAULT_PORT = 8080;
    
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService clientThreadPool;
    private volatile boolean running = false;

    public TCPServer(int port) {
        this.port = port;
        this.clientThreadPool = Executors.newCachedThreadPool();
    }

    public TCPServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Server'ı başlatır
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            logger.info("TCP Server başlatıldı. Port: {}", port);

            while (running) {
                Socket clientSocket = serverSocket.accept();
                logger.info("Yeni client bağlandı: {}", clientSocket.getRemoteSocketAddress());
                
                // Her client için ayrı thread'de işle
                clientThreadPool.submit(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            if (running) {
                logger.error("Server hatası: ", e);
            }
        }
    }

    /**
     * Server'ı durdurur
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            clientThreadPool.shutdown();
            logger.info("TCP Server durduruldu.");
        } catch (IOException e) {
            logger.error("Server kapatılırken hata: ", e);
        }
    }

    /**
     * Her client bağlantısı için mesaj işleme
     */
    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("Client {} mesaj aldı: {}", 
                            clientSocket.getRemoteSocketAddress(), line);
                    
                    // Şimdilik sadece mesajı logla
                    // İleride burada mesaj işleme mantığı eklenecek
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
     * Main metodu - Server'ı başlatır
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

        TCPServer server = new TCPServer(port);
        
        // Shutdown hook ekle
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown sinyali alındı...");
            server.stop();
        }));

        server.start();
    }
}

