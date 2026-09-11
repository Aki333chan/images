package ovh.aurumgg.core.engine;

public enum ExchangeSettlement {
    /** Source currency is burned and target currency is issued. */
    MINT_BURN,
    /** Both legs use a dedicated, liquidity-limited exchange reserve. */
    RESERVE
}
