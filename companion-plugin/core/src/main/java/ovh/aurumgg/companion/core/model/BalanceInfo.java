package ovh.aurumgg.companion.core.model;

/**
 * Баланс игрока по данным Vault.
 *
 * @param balance   численное значение — на нём считает панель
 * @param formatted как валюту показывает сам провайдер («1 234,50 монет»):
 *                  формат задаёт плагин экономики, и придумывать свой значит
 *                  показывать не то, что игрок видит в игре
 * @param currency  название валюты во множественном числе, для подписей
 */
public record BalanceInfo(double balance, String formatted, String currency) {}
