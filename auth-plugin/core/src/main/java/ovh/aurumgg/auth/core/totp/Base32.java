package ovh.aurumgg.auth.core.totp;

import java.util.Locale;

/**
 * Base32 по RFC 4648 — в этой кодировке приложения-аутентификаторы принимают
 * секрет.
 *
 * ПОЧЕМУ СВОЯ РЕАЛИЗАЦИЯ, А НЕ БИБЛИОТЕКА. В JDK есть Base64, но Base32 нет, а
 * тянуть зависимость ради тридцати строк, которые проверяются эталонными
 * векторами RFC, — плохой обмен: лишний jar внутри плагина и лишний повод к
 * конфликту версий с чужим шейдингом.
 *
 * Декодирование намеренно снисходительное: человек вводит секрет руками с
 * телефона, и пробелы, дефисы, нижний регистр и забытые «=» не должны быть
 * поводом сказать «неверный секрет».
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    public static String encode(byte[] data) {
        StringBuilder result = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        // Хвост короче пяти бит дополняем нулями справа — так требует RFC.
        if (bits > 0) result.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        return result.toString();
    }

    /** @throws IllegalArgumentException если во входе есть символ не из алфавита */
    public static byte[] decode(String text) {
        String cleaned = text.replace(" ", "").replace("-", "").replace("=", "")
                .toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream(cleaned.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int value = ALPHABET.indexOf(cleaned.charAt(i));
            if (value < 0) throw new IllegalArgumentException("Не base32: " + cleaned.charAt(i));
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                result.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        // Оставшиеся биты — это дополнение из encode, отдельным байтом они не
        // являются и отбрасываются.
        return result.toByteArray();
    }
}
