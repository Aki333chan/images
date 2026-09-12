package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Что именно должно пережить перезапуск между оплатой и выдачей.
 *
 * Курсор заявки — одно число, и смысл оно имеет только потому, что порядок
 * шагов зафиксирован здесь: сначала деньги, потом предмет, потом команды по
 * очереди. Эти тесты держат именно порядок: собьётся он — перезапущенный
 * сервер выдаст не то, что не успел выдать.
 */
class PurchaseClaimTest {

    @Test
    void платнаяПокупкаНачинаетсяСоСписанияДенег() {
        PurchaseClaim purchase = new PurchaseClaim(Optional.of("npc-shop:abc"), "food", 11,
                new ItemStack(Material.BREAD, 3), List.of("lp user %player% parent add vip", "say hi"));

        assertEquals(4, purchase.stepCount(), "деньги, предмет и две команды");
        assertTrue(purchase.paymentStep(0));
        assertEquals(1, purchase.itemStep());
        assertEquals(Optional.of("lp user %player% parent add vip"), purchase.commandAt(2));
        assertEquals(Optional.of("say hi"), purchase.commandAt(3));
        assertEquals(Optional.empty(), purchase.commandAt(1), "шаг предмета — не команда");
        assertEquals(Optional.empty(), purchase.commandAt(4), "за последним шагом ничего нет");
    }

    @Test
    void бесплатнаяПокупкаНеИмеетШагаОплаты() {
        PurchaseClaim purchase = new PurchaseClaim(Optional.empty(), "food", 11,
                new ItemStack(Material.BREAD), List.of("say hi"));

        assertEquals(2, purchase.stepCount());
        assertFalse(purchase.paymentStep(0));
        assertEquals(0, purchase.itemStep(), "предмет идёт первым: списывать нечего");
        assertEquals(Optional.of("say hi"), purchase.commandAt(1));
    }

    // ПОЧЕМУ ЗДЕСЬ НЕТ ТЕСТА НА ПОЛНЫЙ КРУГ encode → decode.
    //
    // ItemStack.serialize() спрашивает у живого сервера версию данных
    // (Bukkit.getUnsafe().getDataVersion()), и в headless-тесте её взять
    // неоткуда. Подменять ради этого половину Bukkit значило бы проверять
    // мок, а не сериализацию.
    //
    // Именно поэтому предмет и пишется ШТАТНОЙ сериализацией Bukkit, а не
    // собственной кодировкой: непокрытый тестом код должен быть чужим и
    // проверенным, а не своим и свежим. Разбор негодного payload ниже —
    // наш собственный код, и он проверяется.

    @Test
    void негодныйPayloadНеРазбирается() {
        // Обрыв записи, откат версии, неизвестный этому серверу предмет — во
        // всех случаях ответ один: не угадывать. Выдать не то, за что заплачено,
        // хуже, чем позвать администратора.
        assertEquals(Optional.empty(), PurchaseClaim.decode(""));
        assertEquals(Optional.empty(), PurchaseClaim.decode("shop: food\nslot: 1\n"));
        assertEquals(Optional.empty(), PurchaseClaim.decode(": не yaml вовсе ["));
    }

    @Test
    void описаниеДляАдминистратораНазываетПредметИКоманды() {
        PurchaseClaim purchase = new PurchaseClaim(Optional.of("k"), "food", 11,
                new ItemStack(Material.BREAD, 3), List.of("say hi"));

        String summary = purchase.summary();
        assertTrue(summary.contains("3x bread"), summary);
        assertTrue(summary.contains("food#11"), summary);
        assertTrue(summary.contains("1 command"), summary);
    }
}
