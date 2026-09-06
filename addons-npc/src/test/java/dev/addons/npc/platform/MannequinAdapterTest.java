package dev.addons.npc.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class MannequinAdapterTest {
    @Test
    void findsFactoryDeclaredWithRuntimeCompatibleSubtype() throws Exception {
        PaperProfile profile = new CraftProfile();

        Method method = MannequinAdapter.findCompatibleMethod(
                ProfileFactory.class, "resolvableProfile", profile, true);

        assertEquals(PaperProfile.class, method.getParameterTypes()[0]);
        assertEquals("resolved", method.invoke(null, profile));
    }

    private interface BukkitProfile {}

    private interface PaperProfile extends BukkitProfile {}

    private static final class CraftProfile implements PaperProfile {}

    public static final class ProfileFactory {
        public static String resolvableProfile(PaperProfile profile) {
            return "resolved";
        }
    }
}
