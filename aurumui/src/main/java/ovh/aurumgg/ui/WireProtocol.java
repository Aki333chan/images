package ovh.aurumgg.ui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class WireProtocol {
    static final int MAGIC = 0x4155524D;
    static final short VERSION = 4;
    static final int ADMIN_ARENA = 1;
    static final int ADMIN_NPC = 1 << 1;
    static final int ADMIN_SLOTS = 1 << 2;
    static final int SOCIAL = 1 << 3;
    /** Own balance and transfers. Server protocol 4 and newer. */
    static final int ECONOMY = 1 << 4;
    /** Balance adjustments and treasury. Server protocol 4 and newer. */
    static final int ADMIN_ECONOMY = 1 << 5;
    /** Guaranteed player-to-player trade. */
    static final int TRADE = 1 << 6;
    /** Administrative delivery quarantine. */
    static final int ADMIN_CLAIMS = 1 << 7;

    private WireProtocol() {}

    static byte[] hello(String clientVersion) {
        return hello(VERSION, clientVersion);
    }

    static byte[] hello(short protocol, String clientVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeShort(protocol);
                output.writeUTF(clientVersion);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static State state(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > 30_000) throw new IOException("Invalid payload size");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid magic");
            int protocol = input.readUnsignedShort();
            if (protocol < 1 || protocol > VERSION) throw new IOException("Unsupported protocol " + protocol);
            long revision = input.readLong();
            int capabilities = protocol >= 2 ? input.readUnsignedByte() : 0;
            int panelCount = input.readUnsignedByte();
            if (panelCount > 32) throw new IOException("Too many panels");
            List<UiPanel> panels = new ArrayList<>(panelCount);
            for (int i = 0; i < panelCount; i++) {
                String id = input.readUTF();
                int priority = input.readInt();
                String title = input.readUTF();
                int lineCount = input.readUnsignedByte();
                if (lineCount > 24) throw new IOException("Too many lines");
                List<String> lines = new ArrayList<>(lineCount);
                for (int line = 0; line < lineCount; line++) lines.add(input.readUTF());
                panels.add(new UiPanel(id, priority, title, List.copyOf(lines)));
            }
            return new State(protocol, revision, capabilities, List.copyOf(panels));
        }
    }

    record State(int protocol, long revision, int capabilities, List<UiPanel> panels) {}

    static byte[] adminList(String scope) {
        return adminRequest((byte) 1, scope, "", "", Map.of());
    }

    static byte[] adminAction(String scope, String id, String action, Map<String, String> arguments) {
        return adminRequest((byte) 2, scope, id, action, arguments);
    }

    private static byte[] adminRequest(byte opcode, String scope, String id, String action,
                                       Map<String, String> arguments) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeShort(VERSION);
                output.writeByte(opcode);
                output.writeUTF(scope);
                if (opcode == 2) {
                    output.writeUTF(id);
                    output.writeUTF(action);
                    output.writeByte(Math.min(16, arguments.size()));
                    int written = 0;
                    for (Map.Entry<String, String> entry : arguments.entrySet()) {
                        if (written++ >= 16) break;
                        output.writeUTF(entry.getKey());
                        output.writeUTF(entry.getValue());
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static AdminState adminState(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > 30_000) throw new IOException("Invalid admin state size");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported admin state");
            // Не равенство, а «не новее нас»: сервер отвечает согласованной
            // версией, и требовать ровно свою значило бы ломаться о любой
            // сервер, который ещё не обновили.
            int protocol = input.readUnsignedShort();
            if (protocol < 1 || protocol > VERSION) throw new IOException("Unsupported admin protocol " + protocol);
            long revision = input.readLong();
            String scope = input.readUTF();
            boolean success = input.readBoolean();
            String message = input.readUTF();
            int count = input.readUnsignedShort();
            if (count > 512) throw new IOException("Too many admin objects");
            List<AdminObject> objects = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int fields = input.readUnsignedByte();
                if (fields > 64) throw new IOException("Too many admin fields");
                Map<String, String> value = new LinkedHashMap<>();
                for (int field = 0; field < fields; field++) value.put(input.readUTF(), input.readUTF());
                objects.add(new AdminObject(Map.copyOf(value)));
            }
            return new AdminState(revision, scope, success, message, List.copyOf(objects));
        }
    }

    record AdminState(long revision, String scope, boolean success, String message, List<AdminObject> objects) {
        static AdminState empty() { return new AdminState(-1, "", true, "", List.of()); }
    }

    record AdminObject(Map<String, String> fields) {
        String get(String key) { return fields.getOrDefault(key, ""); }
        String id() { return get("id"); }
        String kind() { return get("kind"); }
        String title() { return get("title").isBlank() ? id() : get("title"); }
        boolean bool(String key) { return Boolean.parseBoolean(get(key)); }
    }
}
