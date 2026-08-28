package ovh.aurumgg.auth.paper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import ovh.aurumgg.auth.api.AurumAuthApi;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.api.PremiumVerdict;
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
 * ЧЕГО ЗДЕСЬ НАМЕРЕННО НЕТ. Ни «залогинить игрока», ни «сменить пароль», ни
 * «выдать сессию». Права входа не должны раздаваться извне: чем меньше
 * поверхность, тем меньше способов ошибиться в чужом плагине так, чтобы это
 * стоило пароля.
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
}
