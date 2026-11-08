package dev.endfold.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.endfold.model.EndSpace;
import dev.endfold.model.JoinMode;
import dev.endfold.model.SpaceStatus;
import dev.endfold.model.SpaceType;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldRepositoryTest {
    @TempDir Path tmp;

    private static EndSpace space(UUID id) {
        return new EndSpace(id, SpaceType.GROUP, "x", UUID.randomUUID(), EndSpace.worldNameFor(id), UUID.randomUUID(), 1, SpaceStatus.ACTIVE, 5,
                false, JoinMode.OPEN, "", 0, 0);
    }

    @Test void levelFolderResolvedFrom26xOverworldPath() {
        Path level = tmp.resolve("world");
        assertEquals(level.toAbsolutePath().normalize(), WorldRepository.levelFolderOf(level.resolve("dimensions/minecraft/overworld")));
        assertEquals(level.toAbsolutePath().normalize(), WorldRepository.levelFolderOf(level));
    }

    @Test void backupRestoreRoundTripAndTrash() throws IOException {
        WorldRepository repo = new WorldRepository(tmp.resolve("world"), tmp.resolve("plugin"));
        EndSpace s = space(UUID.randomUUID());
        Path folder = repo.folderOf(s);
        assertEquals(tmp.resolve("world/dimensions/endspace/" + s.shortId()).toAbsolutePath().normalize(), folder);
        Files.createDirectories(folder.resolve("region"));
        Files.writeString(folder.resolve("region/r.0.0.mca"), "original");
        Files.writeString(folder.resolve("session.lock"), "lock");
        Path backup = repo.backup(s, "manual");
        assertEquals(List.of(backup), repo.listBackups(s));
        Files.writeString(folder.resolve("region/r.0.0.mca"), "changed");
        Path trashed = repo.restore(s, repo.resolveBackup(s, backup.getFileName().toString()));
        assertEquals("original", Files.readString(folder.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(folder.resolve("session.lock")), "session.lock must not be backed up");
        assertEquals("changed", Files.readString(trashed.resolve("region/r.0.0.mca")), "previous state kept in trash");
        assertTrue(trashed.startsWith(tmp.resolve("plugin/trash").toAbsolutePath().normalize()));
    }

    @Test void restoreRejectsZipSlip() throws IOException {
        WorldRepository repo = new WorldRepository(tmp.resolve("world"), tmp.resolve("plugin"));
        EndSpace s = space(UUID.randomUUID());
        Files.createDirectories(repo.folderOf(s));
        Files.createDirectories(repo.backupDir(s));
        Path evil = repo.backupDir(s).resolve(s.shortId() + "-20260101-000000.zip");
        try (OutputStream os = Files.newOutputStream(evil); ZipOutputStream z = new ZipOutputStream(os)) {
            z.putNextEntry(new ZipEntry("../../../../escaped.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        assertThrows(IOException.class, () -> repo.restore(s, evil));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
        assertTrue(Files.isDirectory(repo.folderOf(s)), "world folder untouched after rejected restore");
    }

    @Test void backupNamesAreValidated() {
        WorldRepository repo = new WorldRepository(tmp.resolve("world"), tmp.resolve("plugin"));
        EndSpace s = space(UUID.randomUUID());
        assertThrows(IOException.class, () -> repo.resolveBackup(s, "../../level.dat"));
        assertThrows(IOException.class, () -> repo.resolveBackup(s, "deadbeef-20260101-000000.zip"));
    }

    @Test void orphanFoldersAreReportedNotTouched() throws IOException {
        WorldRepository repo = new WorldRepository(tmp.resolve("world"), tmp.resolve("plugin"));
        Files.createDirectories(repo.dimensionsRoot().resolve("abcdef01"));
        Files.createDirectories(repo.dimensionsRoot().resolve("12345678"));
        assertEquals(List.of("abcdef01"), repo.orphanFolders(Set.of("12345678")));
        assertTrue(Files.isDirectory(repo.dimensionsRoot().resolve("abcdef01")));
    }
}
