package ovh.aurumgg.companion.core.ui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned, bounded binary protocol shared with the Fabric client. */
public final class UiWireProtocol {
    public static final int MAGIC = 0x4155524D; // AURM
    public static final int VERSION = 4;
    public static final int ADMIN_ARENA = 1;
    public static final int ADMIN_NPC = 1 << 1;
    public static final int ADMIN_SLOTS = 1 << 2;
    public static final int SOCIAL = 1 << 3;
    /** Own balance and transfers. Protocol 4 and newer. */
    public static final int ECONOMY = 1 << 4;
    /** Balance adjustments and treasury. Protocol 4 and newer. */
    public static final int ADMIN_ECONOMY = 1 << 5;

    /**
     * Capability bits an older client is able to render.
     *
     * <p>Protocol 3 and older read four bits and know nothing about the rest.
     * Sending bits they cannot render is not harmful, but masking keeps the
     * payload honest about what the peer was actually told.</p>
     */
    private static int capabilityMask(int protocol) {
        return protocol >= 4 ? 0xFF : 0x0F;
    }

    private UiWireProtocol() {}

    public static int helloVersion(byte[] bytes) {
        if (bytes == null || bytes.length < 6 || bytes.length > 512) return 0;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) return 0;
            int protocol = input.readUnsignedShort();
            return protocol >= 1 && protocol <= VERSION ? protocol : 0;
        } catch (IOException ignored) {
            return 0;
        }
    }

    public static byte[] state(int protocol, long revision, int capabilities, List<UiPanel> panels) throws IOException {
        if (protocol < 1 || protocol > VERSION) throw new IOException("Unsupported protocol " + protocol);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeShort(protocol);
            output.writeLong(revision);
            if (protocol >= 2) output.writeByte(capabilities & capabilityMask(protocol));
            output.writeByte(Math.min(32, panels.size()));
            for (UiPanel panel : panels.stream().limit(32).toList()) {
                output.writeUTF(panel.id());
                output.writeInt(panel.priority());
                output.writeUTF(panel.title());
                output.writeByte(panel.lines().size());
                for (String line : panel.lines()) output.writeUTF(line);
            }
        }
        return bytes.toByteArray();
    }

    public static AdminRequest adminRequest(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 7 || bytes.length > 30_000) throw new IOException("Invalid admin payload");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readUnsignedShort() != VERSION) {
                throw new IOException("Unsupported admin protocol");
            }
            int opcode = input.readUnsignedByte();
            String scope = bounded(input.readUTF(), 96, "scope");
            if (opcode == 1) return new AdminRequest(scope, "", "", Map.of());
            if (opcode != 2) throw new IOException("Unknown admin opcode");
            String id = bounded(input.readUTF(), 96, "id");
            String action = bounded(input.readUTF(), 64, "action");
            int count = input.readUnsignedByte();
            if (count > 16) throw new IOException("Too many arguments");
            Map<String, String> arguments = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                arguments.put(bounded(input.readUTF(), 48, "argument key"),
                        bounded(input.readUTF(), 512, "argument value"));
            }
            return new AdminRequest(scope, id, action, Map.copyOf(arguments));
        }
    }

    /**
     * Administrative state for one client.
     *
     * <p>The negotiated protocol is written, not this build's {@link #VERSION}:
     * the client checks the version it reads against its own and refuses
     * anything else, so stamping a newer number would silently break the
     * administration tabs of every client that has not been updated yet.</p>
     */
    public static byte[] adminState(int protocol, long revision, String scope, boolean success, String message,
                                    List<Map<String, String>> objects) throws IOException {
        if (protocol < 1 || protocol > VERSION) throw new IOException("Unsupported protocol " + protocol);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeShort(protocol);
            output.writeLong(revision);
            output.writeUTF(bounded(scope, 96, "scope"));
            output.writeBoolean(success);
            output.writeUTF(bounded(message == null ? "" : message, 512, "message"));
            output.writeShort(Math.min(512, objects.size()));
            for (Map<String, String> object : objects.stream().limit(512).toList()) {
                output.writeByte(Math.min(64, object.size()));
                int written = 0;
                for (Map.Entry<String, String> entry : object.entrySet()) {
                    if (written++ >= 64) break;
                    output.writeUTF(bounded(entry.getKey(), 48, "field key"));
                    output.writeUTF(bounded(entry.getValue(), 1024, "field value"));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static String bounded(String value, int maximum, String field) throws IOException {
        if (value == null || value.length() > maximum) throw new IOException("Invalid " + field);
        return value;
    }

    public record AdminRequest(String scope, String id, String action, Map<String, String> arguments) {}
}
