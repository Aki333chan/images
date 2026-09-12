package ovh.aurumgg.companion.core.model;

/** Definitive result of consuming a preview token. */
public record EconomyRuleApply(String status, EconomyRuleInfo current, String message) {}
