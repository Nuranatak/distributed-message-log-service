package com.sistem.proje.client;

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
    private static final String LEADER_HOST = "localhost";
    private static final int LEADER_PORT = 6666;

    public static void main(String[] args) {
        System.out.println("Client başlatılıyor...");
        System.out.println("LeaderNode'a bağlanılıyor: " + LEADER_HOST + ":" + LEADER_PORT);

        try (Socket socket = new Socket(LEADER_HOST, LEADER_PORT);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // 1. SET komutu - mesaj kaydet
            String setCommand = "SET 34 İstanbul ";
            System.out.println("[SET] Gönderiliyor: " + setCommand);
            writer.println(setCommand);
            String setResponse = reader.readLine();
            System.out.println("[SET] Cevap: " + setResponse);

            // 2. GET komutu - mesajı oku
            String getCommand = "GET 34";
            System.out.println("[GET] Gönderiliyor: " + getCommand);
            writer.println(getCommand);
            String getResponse = reader.readLine();
            System.out.println("[GET] Cevap: " + getResponse);

            // 3. GET komutu - olmayan mesaj (NOT_FOUND testi)
            String getCommand2 = "GET 33";
            System.out.println("[GET] Gönderiliyor: " + getCommand2);
            writer.println(getCommand2);
            String getResponse2 = reader.readLine();
            System.out.println("[GET] Cevap: " + getResponse2);

            System.out.println("✓ Tüm testler tamamlandı!");

        } catch (IOException e) {
            System.out.println("Bağlantı hatası: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Beklenmeyen hata: " + e.getMessage());
        }

        System.out.println("Client kapatılıyor...");
    }
}

