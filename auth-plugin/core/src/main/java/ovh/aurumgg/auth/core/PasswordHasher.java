package ovh.aurumgg.auth.core;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Хеширование и проверка паролей через bcrypt.
 *
 * КЛАСС НИЧЕГО НЕ ЗНАЕТ ПРО ПОТОКИ — и это намеренно. Он просто медленный, а
 * следить за тем, чтобы его не позвали с главного потока сервера, — работа
 * AuthService, у которого для этого есть отдельный пул. Разделение важное:
 * если «асинхронность» размазать по всем классам, однажды кто-нибудь добавит
 * ещё один вызов и забудет, а тормозить будет сразу у всех игроков.
 *
 * ПРО ЦЕНУ ВОПРОСА. bcrypt медленный по определению — в этом его смысл: он
 * делает перебор украденной базы невыгодным. При стоимости 12 одна проверка
 * занимает примерно четверть секунды. Один синхронный вызов — это четверть
 * секунды полностью замершего сервера (5 тиков), а пятеро заходящих
 * одновременно — уже больше секунды, и это чувствуют все, включая тех, кто
 * давно играет и ничего не логинит.
 */
public final class PasswordHasher {

    private final int cost;

    public PasswordHasher(int cost) {
        this.cost = cost;
    }

    /**
     * Хеш пароля.
     *
     * Пароль принимается char[], а не String: строки живут в пуле до сборки
     * мусора, и снять их дампом кучи можно ещё долго после входа. Массив
     * затирается сразу после использования — это не абсолютная защита, но
     * стоит она ровно ничего.
     */
    public String hash(char[] password) {
        try {
            return BCrypt.withDefaults().hashToString(cost, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Проверка пароля.
     *
     * Сравнение делает сам bcrypt и делает его за постоянное время: наивное
     * равенство строк выдавало бы длину совпавшего префикса разницей во
     * времени ответа.
     */
    public boolean verify(char[] password, String hash) {
        try {
            if (hash == null || hash.isBlank()) return false;
            return BCrypt.verifyer()
                    .verify(password, hash.getBytes(StandardCharsets.UTF_8))
                    .verified;
        } catch (IllegalArgumentException e) {
            // Хеш в БД испорчен или записан не bcrypt. Пускать по такому
            // нельзя, а падать всем плагином из-за одной битой строки — тем
            // более.
            return false;
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
