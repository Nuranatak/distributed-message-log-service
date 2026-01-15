package com.sistem.proje.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * TCP tabanlı abonelik sistemi - Client uygulaması
 * LeaderNode'a basit test mesajı gönderir
 */
public class Client {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);
    private static final String LEADER_HOST = "localhost";
    private static final int LEADER_PORT = 8080;

    public static void main(String[] args) {
        logger.info("Client başlatılıyor...");
        logger.info("LeaderNode'a bağlanılıyor: {}:{}", LEADER_HOST, LEADER_PORT);

        try (Socket socket = new Socket(LEADER_HOST, LEADER_PORT);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // 1. SET komutu - mesaj kaydet
            String setCommand = "SET 1 Merhaba Dünya - Test Mesajı";
            logger.info("[SET] Gönderiliyor: {}", setCommand);
            writer.println(setCommand);
            String setResponse = reader.readLine();
            logger.info("[SET] Cevap: {}", setResponse);

            // 2. GET komutu - mesajı oku
            String getCommand = "GET 1";
            logger.info("[GET] Gönderiliyor: {}", getCommand);
            writer.println(getCommand);
            String getResponse = reader.readLine();
            logger.info("[GET] Cevap: {}", getResponse);

            // 3. GET komutu - olmayan mesaj (NOT_FOUND testi)
            String getCommand2 = "GET 999";
            logger.info("[GET] Gönderiliyor: {}", getCommand2);
            writer.println(getCommand2);
            String getResponse2 = reader.readLine();
            logger.info("[GET] Cevap: {}", getResponse2);

            logger.info("✓ Tüm testler tamamlandı!");

        } catch (IOException e) {
            logger.error("Bağlantı hatası: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Beklenmeyen hata: {}", e.getMessage());
        }

        logger.info("Client kapatılıyor...");
    }
}

