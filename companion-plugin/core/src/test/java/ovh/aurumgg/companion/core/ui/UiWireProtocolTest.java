package ovh.aurumgg.companion.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiWireProtocolTest {
    @Test
    void recognizesCompatibleProtocolHello() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(UiWireProtocol.MAGIC);
            output.writeShort(UiWireProtocol.VERSION);
            output.writeUTF("0.1.0");
        }
        assertEquals(UiWireProtocol.VERSION, UiWireProtocol.helloVersion(bytes.toByteArray()));
        assertEquals(0, UiWireProtocol.helloVersion(new byte[] {1, 2, 3}));
    }

    @Test
    void writesBoundedState() throws Exception {
        byte[] result = UiWireProtocol.state(UiWireProtocol.VERSION, 12,
                UiWireProtocol.ADMIN_ARENA | UiWireProtocol.ADMIN_NPC, List.of(
                new UiPanel("guilds", 50, "&6Guild", List.of("&7Online: &f3/8"))));
        assertTrue(result.length > 15);
        assertTrue(result.length < 512);
    }

    @Test
    void decodesTypedAdminAction() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(UiWireProtocol.MAGIC);
            output.writeShort(UiWireProtocol.VERSION);
            output.writeByte(2);
            output.writeUTF("arena");
            output.writeUTF("colosseum");
            output.writeUTF("set_final_pool");
            output.writeByte(1);
            output.writeUTF("value");
            output.writeUTF("25000");
        }
        UiWireProtocol.AdminRequest request = UiWireProtocol.adminRequest(bytes.toByteArray());
        assertEquals("arena", request.scope());
        assertEquals("colosseum", request.id());
        assertEquals("set_final_pool", request.action());
        assertEquals("25000", request.arguments().get("value"));
    }

    @Test
    void writesBoundedAdminSnapshot() throws Exception {
        byte[] result = UiWireProtocol.adminState(7, "slots", true, "ok.saved", List.of(Map.of(
                "kind", "slots", "id", "one", "bet", "10")));
        assertTrue(result.length > 20);
        assertTrue(result.length < 512);
    }
}
