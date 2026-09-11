package org.ChisaO_o.simpleSlots;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OptionalCoreLinkageTest {
    @Test
    void paperCanReflectMainListenerWithoutCoreApiPresent() {
        ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("ovh.aurumgg.core.api.")) throw new ClassNotFoundException(name);
                    if (name.startsWith("org.ChisaO_o.simpleSlots.")) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findPluginClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }

            private Class<?> findPluginClass(String name) throws ClassNotFoundException {
                Path file = Path.of("target/classes", name.replace('.', '/') + ".class");
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException error) {
                    throw new ClassNotFoundException(name, error);
                }
            }
        };

        assertDoesNotThrow(() -> Class.forName(SimpleSlots.class.getName(), false, isolated)
                .getDeclaredMethods());
    }
}
