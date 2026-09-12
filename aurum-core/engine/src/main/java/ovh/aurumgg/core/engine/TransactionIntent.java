package ovh.aurumgg.core.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import ovh.aurumgg.core.api.TransactionRequest;

/** Canonical identity bound to a ledger idempotency key. */
public final class TransactionIntent {
    private TransactionIntent() {}

    public static String hash(TransactionRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            part(digest, request.from().type().name());
            part(digest, request.from().reference());
            part(digest, request.to().type().name());
            part(digest, request.to().reference());
            part(digest, request.currencyId());
            part(digest, request.amount().stripTrailingZeros().toPlainString());
            part(digest, request.category().name());
            for (Map.Entry<String, String> entry : request.metadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                part(digest, entry.getKey());
                part(digest, entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void part(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
