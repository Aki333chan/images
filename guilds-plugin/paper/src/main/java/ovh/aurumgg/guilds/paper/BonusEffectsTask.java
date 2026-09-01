package ovh.aurumgg.guilds.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ovh.aurumgg.guilds.api.BonusType;
import ovh.aurumgg.guilds.core.GuildService;

/**
 * Бонусы-эффекты: раздать их тем, кто сейчас в сети.
 *
 * <h2>Почему задачей, а не разово при входе</h2>
 *
 * Эффект зелья кончается сам. Выдать «Спешку» один раз при входе значило бы,
 * что через несколько минут она пропадёт, а игрок будет считать, что купленный
 * гильдией бонус не работает. Ставить бесконечную длительность тоже нельзя:
 * бонус ведь может истечь или его снимут — а эффект останется до смерти
 * игрока.
 *
 * Поэтому эффект выдаётся коротким и обновляется задачей. Кончится бонус —
 * перестанем продлевать, и он погаснет сам в течение нескольких секунд.
 *
 * <h2>Про чужие эффекты</h2>
 *
 * Игрок, выпивший зелье скорости II, не должен получить понижение до
 * гильдейской скорости I. Об этом заботиться не нужно — так работает сама
 * игра: эффект того же вида с меньшим уровнем не перебивает более сильный, а
 * откладывается «под» ним и включится, когда сильный кончится. В API это видно
 * по {@link org.bukkit.potion.PotionEffect#getHiddenPotionEffect()} — тому
 * самому отложенному эффекту.
 *
 * Перегрузки {@code addPotionEffect(effect, force)} здесь намеренно нет: она
 * помечена устаревшей с 1.15.2 и просто зовёт однопараметрическую, то есть
 * флаг не делает ничего. Код, который на него полагается, выглядит
 * защищённым, не будучи таковым.
 *
 * <h2>Про мигание значка</h2>
 *
 * {@code ambient = true} и без частиц: постоянно висящий бонус не должен
 * выглядеть как только что выпитое зелье. Значок в углу остаётся — человек
 * должен видеть, что усиление действует.
 */
final class BonusEffectsTask implements Runnable {

    /**
     * На сколько выдаётся эффект, в тиках.
     *
     * Заметно дольше периода обновления, иначе между двумя тактами задачи
     * эффект успевал бы моргнуть. Но и не слишком долго: столько же держится
     * бонус после того, как его сняли.
     */
    private static final int EFFECT_TICKS = 15 * 20;

    /** Как часто продлевать. Втрое чаще, чем кончается эффект. */
    static final long PERIOD_TICKS = 5 * 20;

    private final GuildService guilds;

    BonusEffectsTask(GuildService guilds) {
        this.guilds = guilds;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player, BonusType.MINING_SPEED, PotionEffectType.HASTE);
            apply(player, BonusType.MOVEMENT_SPEED, PotionEffectType.SPEED);
        }
    }

    private void apply(Player player, BonusType bonus, PotionEffectType effect) {
        guilds.bonusOf(player.getUniqueId(), bonus).ifPresent(active -> {
            // Уровень эффекта считается с нуля: «Спешка I» — это amplifier 0.
            int amplifier = Math.max(0, (int) Math.round(active.magnitude()) - 1);
            // ambient — «не от зелья»: значок в углу приглушённый, и постоянно
            // висящий бонус не выглядит как только что выпитое зелье. Частиц
            // нет: облако вокруг игрока сутками — это раздражает и выдаёт его
            // в PvP. Значок при этом показывается: человек должен видеть, что
            // усиление действует.
            player.addPotionEffect(
                    new PotionEffect(effect, EFFECT_TICKS, amplifier, true, false, true));
        });
    }
}
