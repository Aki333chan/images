package dev.addons.npc.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BuyerDefinition {
    private final String id;
    private String title;
    private int size;
    private TimedPercentage bonus = TimedPercentage.none();
    private BuyerBudgetMode budgetMode = BuyerBudgetMode.DEFAULT;
    private String budgetTreasuryId = "";
    private final Map<Integer, BuyerOffer> offers = new LinkedHashMap<>();

    public BuyerDefinition(String id, String title, int size) {
        this.id = NpcDefinition.normalizeId(id);
        this.title = title;
        size(size);
    }

    public String id() { return id; }
    public String title() { return title; }
    public void title(String title) { this.title = title; }
    public int size() { return size; }
    public void size(int size) {
        int clamped = Math.max(9, Math.min(54, size));
        this.size = ((clamped + 8) / 9) * 9;
    }
    public Map<Integer, BuyerOffer> offers() { return offers; }
    public TimedPercentage bonus() { return bonus; }
    public void bonus(TimedPercentage bonus) { this.bonus = bonus == null ? TimedPercentage.none() : bonus; }
    public BuyerBudgetMode budgetMode() { return budgetMode; }
    public void budgetMode(BuyerBudgetMode budgetMode) {
        this.budgetMode = budgetMode == null ? BuyerBudgetMode.DEFAULT : budgetMode;
    }
    public String budgetTreasuryId() { return budgetTreasuryId; }
    public void budgetTreasuryId(String budgetTreasuryId) {
        String value = budgetTreasuryId == null ? "" : budgetTreasuryId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.isEmpty() && !value.matches("[a-z0-9][a-z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("Invalid treasury id");
        }
        this.budgetTreasuryId = value;
    }
}
