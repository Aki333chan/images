package ovh.aurumgg.auth.paper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import ovh.aurumgg.auth.api.AurumAuthApi;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.api.PremiumVerdict;
import ovh.aurumgg.auth.api.ResetToken;
import ovh.aurumgg.auth.core.AuthService;

/**
 * Реализация публичного API поверх сервиса.
 *
 * Тонкая обёртка и ничего больше: всё, что она отдаёт, лежит в памяти и
 * читается мгновенно. Это принципиально — API зовут с главного потока (в том
 * числе companion при обработке команды), и если бы за ответом «залогинен ли»
 * пришлось идти в MariaDB, мы бы своими руками вернули ровно ту проблему, от
 * которой ушли, отказавшись читать чужую таблицу.
 *
 * ЧЕГО ЗДЕСЬ НАМЕРЕННО НЕТ. Ни «залогинить игрока», ни «задать ему пароль»,
 * ни «выдать сессию». Права входа не должны раздаваться извне: чем меньше
 * поверхность, тем меньше способов ошибиться в чужом плагине так, чтобы это
 * стоило пароля.
 *
 * Единственное исключение — issueResetToken. Он тоже привилегированный, но
 * принципиально слабее: выдаёт не вход, а одноразовый ключ на двадцать минут,
 * которым всё равно придётся воспользоваться самому игроку, зайдя под своим
 * ником. Без него сброс пароля из панели был бы невозможен, а обходной путь
 * (лезть в чужую базу) — ровно то, от чего мы уходили.
 */
final class BukkitAuthApi implements AurumAuthApi {

    private final AuthService service;

    BukkitAuthApi(AuthService service) {
        this.service = service;
    }

    @Override
    public boolean isAuthenticated(UUID playerUuid) {
        return service.isAuthenticated(playerUuid);
    }

    @Override
    public CompletableFuture<Boolean> isRegistered(String username) {
        // Единственный метод, которому нужна БД, — и поэтому единственный
        // асинхронный. Блокирующая версия превратила бы безобидный вопрос
        // «занят ли ник» в поход в MariaDB с главного потока.
        return service.isRegistered(username);
    }

    @Override
    public Optional<AuthStatus> status(UUID playerUuid) {
        return service.status(playerUuid);
    }

    @Override
    public Optional<PremiumVerdict> premiumVerdict(UUID playerUuid) {
        return service.premiumVerdict(playerUuid);
    }

    @Override
    public CompletableFuture<Optional<ResetToken>> issueResetToken(String username) {
        return service.issueResetToken(username);
    }
}
