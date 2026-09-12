package ovh.aurumgg.companion.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiWireProtocolTest {
    @Test void socialCapabilitySurvivesEncodingWithoutAdminRights() throws Exception {
        byte[] result = UiWireProtocol.state(3, 1, UiWireProtocol.SOCIAL, List.of());
        assertEquals(UiWireProtocol.SOCIAL, result[14] & 0xff);
        assertEquals(0, result[14] & 0x07);
    }
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
        byte[] result = UiWireProtocol.adminState(UiWireProtocol.VERSION, 7, "slots", true, "ok.saved",
                List.of(Map.of("kind", "slots", "id", "one", "bet", "10")));
        assertTrue(result.length > 20);
        assertTrue(result.length < 512);
    }

    @Test
    void adminStateCarriesTheNegotiatedProtocolAndNotThisBuild() throws Exception {
        // Клиент сверяет версию в ответе со своей и отказывается от чужой.
        // Поэтому старому клиенту отвечаем его номером, иначе каждое повышение
        // версии молча ломало бы админские вкладки всем, кто не обновился.
        byte[] result = UiWireProtocol.adminState(3, 7, "slots", true, "ok.saved", List.of());
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(result))) {
            assertEquals(UiWireProtocol.MAGIC, input.readInt());
            assertEquals(3, input.readUnsignedShort());
        }
    }

    @Test
    void olderClientsNeverSeeCapabilityBitsTheyCannotRender() throws Exception {
        int everything = UiWireProtocol.SOCIAL | UiWireProtocol.ECONOMY | UiWireProtocol.ADMIN_ECONOMY;

        assertEquals(UiWireProtocol.SOCIAL, capabilities(3, everything));
        assertEquals(everything, capabilities(UiWireProtocol.VERSION, everything));
    }

    private static int capabilities(int protocol, int bits) throws Exception {
        byte[] state = UiWireProtocol.state(protocol, 1L, bits, List.of());
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(state))) {
            input.readInt();
            input.readUnsignedShort();
            input.readLong();
            return input.readUnsignedByte();
        }
    }
}
