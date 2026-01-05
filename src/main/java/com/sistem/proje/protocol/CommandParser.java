package com.sistem.proje.protocol;

/**
 * Komut parse eden sınıf
 * SET <id> <message> ve GET <id> formatlarını destekler
 */
public class CommandParser {

    /**
     * Gelen satırı parse eder ve uygun Command nesnesini döndürür
     * 
     * @param line Parse edilecek satır
     * @return SetCommand veya GetCommand nesnesi
     * @throws CommandParseException Hatalı format durumunda
     */
    public Command parse(String line) throws CommandParseException {
        if (line == null || line.trim().isEmpty()) {
            throw new CommandParseException("Boş satır parse edilemez");
        }

        String trimmedLine = line.trim();
        String[] parts = trimmedLine.split("\\s+", 3);

        if (parts.length == 0) {
            throw new CommandParseException("Komut boş olamaz");
        }

        String command = parts[0].toUpperCase();

        try {
            switch (command) {
                case "SET":
                    return parseSetCommand(parts);
                case "GET":
                    return parseGetCommand(parts);
                default:
                    throw new CommandParseException("Bilinmeyen komut: " + command + ". Desteklenen komutlar: SET, GET");
            }
        } catch (IllegalArgumentException e) {
            throw new CommandParseException("Komut parse hatası: " + e.getMessage(), e);
        }
    }

    /**
     * SET komutunu parse eder: SET <id> <message>
     */
    private SetCommand parseSetCommand(String[] parts) throws CommandParseException {
        if (parts.length < 3) {
            throw new CommandParseException("SET komutu formatı: SET <id> <message>");
        }

        String id = parts[1];
        String message = parts[2];

        return new SetCommand(id, message);
    }

    /**
     * GET komutunu parse eder: GET <id>
     */
    private GetCommand parseGetCommand(String[] parts) throws CommandParseException {
        if (parts.length < 2) {
            throw new CommandParseException("GET komutu formatı: GET <id>");
        }

        String id = parts[1];

        return new GetCommand(id);
    }
}

