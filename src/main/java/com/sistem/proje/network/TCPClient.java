package com.sistem.proje.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Basit TCP Client - Test amaçlı
 */
public class TCPClient {
    private static final Logger logger = LoggerFactory.getLogger(TCPClient.class);
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;

    private final String host;
    private final int port;

    public TCPClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public TCPClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    /**
     * Server'a bağlanır ve mesaj gönderir
     */
    public void connectAndSend(String message) {
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))) {
            
            logger.info("Server'a bağlandı: {}:{}", host, port);
            
            // Mesaj gönder
            out.println(message);
            logger.info("Mesaj gönderildi: {}", message);
            
        } catch (IOException e) {
            logger.error("Bağlantı hatası: ", e);
        }
    }

    /**
     * Main metodu - Test amaçlı
     */
    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String message = "Test mesajı";

        if (args.length > 0) {
            host = args[0];
        }
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                logger.error("Geçersiz port numarası: {}", args[1]);
                return;
            }
        }
        if (args.length > 2) {
            message = args[2];
        }

        TCPClient client = new TCPClient(host, port);
        client.connectAndSend(message);
    }
}

