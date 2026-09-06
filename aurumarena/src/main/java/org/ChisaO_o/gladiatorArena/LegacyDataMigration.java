package org.ChisaO_o.gladiatorArena;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** One-time import after rebranding; legacy files remain an untouched backup. */
final class LegacyDataMigration {
    private LegacyDataMigration() {}

    static boolean migrate(Path legacy, Path destination) throws IOException {
        legacy = legacy.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)
                || !legacy.getParent().equals(destination.getParent())) {
            throw new IOException("Legacy data must be a real sibling directory: " + legacy);
        }
        Path staging = destination.resolveSibling("." + destination.getFileName() + "-migration");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Incomplete migration directory already exists; inspect it before retrying: " + staging);
        }
        Path source = legacy;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectory(staging.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) throw new IOException("Unsupported legacy file: " + file);
                Files.copy(file, staging.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
        // Publish only after every file has been copied successfully. No files are overwritten.
        Files.move(staging, destination);
        return true;
    }
}
