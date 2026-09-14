package dev.addons.npc.model;

import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** Main-thread stock/quota. Expiry is lazy: no scheduler or DB query per offer. */
public final class OfferStock {
    private int remaining;
    private int maximum;
    private long intervalSeconds;
    private long dueAt;
    private String cycle = UUID.randomUUID().toString();

    public OfferStock(int amount) { reset(amount); }
    public int remaining() { refresh(System.currentTimeMillis()); return remaining; }
    public int maximum() { return maximum; }
    public boolean unlimited() { return maximum < 0; }
    public long intervalSeconds() { return intervalSeconds; }
    public long dueAt() { refresh(System.currentTimeMillis()); return dueAt; }
    public String cycle() { refresh(System.currentTimeMillis()); return cycle; }

    public void reset(int amount) {
        maximum = remaining = amount < 0 ? -1 : amount;
        dueAt = 0;
        cycle = UUID.randomUUID().toString();
    }

    public void configure(int maximum, long seconds, long now) {
        if (maximum <= 0 || seconds < 0 || seconds > 31_536_000L)
            throw new IllegalArgumentException("Stock must be positive; refill is 0..31536000 seconds");
        reset(maximum);
        intervalSeconds = seconds;
    }

    public void disableRefill() { intervalSeconds = 0; dueAt = 0; }

    public boolean refresh(long now) {
        if (dueAt == 0 || now < dueAt) return false;
        remaining = maximum;
        dueAt = 0;
        cycle = UUID.randomUUID().toString();
        return true;
    }

    /** Returns the cycle receipt; rollback from an old cycle must not refill a new one. */
    public String consume(int amount, long now) {
        refresh(now);
        if (amount < 1 || (!unlimited() && remaining < amount))
            throw new IllegalArgumentException("Insufficient offer stock");
        if (unlimited()) return cycle;
        remaining -= amount;
        if (intervalSeconds > 0 && dueAt == 0) dueAt = now + intervalSeconds * 1000L;
        return cycle;
    }

    public void restore(int amount, String receipt, long now) {
        refresh(now);
        if (unlimited() || amount <= 0 || !cycle.equals(receipt)) return;
        remaining = (int) Math.min(maximum, (long) remaining + amount);
        if (remaining == maximum) dueAt = 0;
    }

    public void load(ConfigurationSection section) {
        if (section == null) return;
        maximum = section.getInt("maximum", maximum);
        if (maximum < 0) remaining = -1;
        else remaining = Math.min(maximum, Math.max(0, remaining));
        intervalSeconds = Math.max(0, Math.min(31_536_000L, section.getLong("interval-seconds", 0)));
        dueAt = section.getLong("due-at", 0);
        cycle = section.getString("cycle", cycle);
        if (unlimited() || remaining == maximum || intervalSeconds == 0) dueAt = 0;
        // Missing deadline is not started merely by loading an untouched offer.
    }

    public void save(ConfigurationSection section) {
        section.set("maximum", maximum);
        section.set("interval-seconds", intervalSeconds);
        section.set("due-at", dueAt);
        section.set("cycle", cycle);
    }
}
