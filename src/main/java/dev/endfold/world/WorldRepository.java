package dev.endfold.world;

import dev.endfold.model.EndSpace;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * File-system side of EndSpace worlds: folder locations, backups, restores and the trash directory. Never deletes a
 * world folder outright; destructive operations move folders to {@code plugins/Endfold/trash}.
 *
 * <p>All methods here do blocking I/O and must run off the server thread (except trivial path computations). The
 * caller is responsible for making sure the world is saved and/or unloaded first.</p>
 */
public final class WorldRepository {
    public static final String NAMESPACE = "endspace";
    private static final Pattern SHORT_ID = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern BACKUP_NAME = Pattern.compile("[0-9a-f]{8}-\\d{8}-\\d{6}(-[a-z]+)?\\.zip");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path levelFolder;
    private final Path backupRoot;
    private final Path trashRoot;

    /**
     * @param levelFolder folder of the main level (contains {@code level.dat} and {@code dimensions/})
     */
    public WorldRepository(Path levelFolder, Path pluginFolder) {
        this.levelFolder = levelFolder.toAbsolutePath().normalize();
        this.backupRoot = pluginFolder.resolve("backups").toAbsolutePath().normalize();
        this.trashRoot = pluginFolder.resolve("trash").toAbsolutePath().normalize();
    }

    /**
     * Resolves the level folder (the one containing {@code level.dat} and {@code dimensions/}) from the main world's
     * path. Since 26.1 the overworld itself lives in {@code <level>/dimensions/minecraft/overworld}.
     */
    public static Path levelFolderOf(Path mainWorldPath) {
        Path p = mainWorldPath.toAbsolutePath().normalize();
        Path ns = p.getParent();
        Path dims = ns == null ? null : ns.getParent();
        if (dims != null && dims.getFileName() != null && dims.getFileName().toString().equals("dimensions")) return dims.getParent();
        return p;
    }

    /** Sanity check used after loading: the server must have put the world where we expect its files. */
    public boolean matches(EndSpace space, Path actualWorldPath) {
        return actualWorldPath.toAbsolutePath().normalize().equals(folderOf(space));
    }

    public Path dimensionsRoot() { return levelFolder.resolve("dimensions").resolve(NAMESPACE); }

    public Path folderOf(EndSpace space) {
        String id = space.shortId();
        if (!SHORT_ID.matcher(id).matches()) throw new IllegalArgumentException("Bad space id " + id);
        return dimensionsRoot().resolve(id);
    }

    /** True when the world folder exists and contains region data or level data (i.e. it has been generated). */
    public boolean exists(EndSpace space) {
        Path f = folderOf(space);
        return Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS);
    }

    public Path backupDir(EndSpace space) { return backupRoot.resolve(space.shortId()); }

    /** Zips the world folder. The world must have been saved (and ideally have auto-save paused) by the caller. */
    public Path backup(EndSpace space, String tag) throws IOException {
        Path src = folderOf(space);
        if (!Files.isDirectory(src)) throw new IOException("World folder does not exist: " + src);
        Path dir = backupDir(space);
        Files.createDirectories(dir);
        String name = space.shortId() + "-" + LocalDateTime.now().format(TS) + (tag == null ? "" : "-" + tag) + ".zip";
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".part");
        List<String> skipped = new ArrayList<>();
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                    String rel = src.relativize(file).toString().replace('\\', '/');
                    byte[] data;
                    try {
                        // Read the whole file first so a concurrent write can never produce a truncated zip entry.
                        data = Files.readAllBytes(file);
                    } catch (IOException e) {
                        skipped.add(rel);
                        return FileVisitResult.CONTINUE;
                    }
                    zip.putNextEntry(new ZipEntry(rel));
                    zip.write(data);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (!skipped.isEmpty()) {
            Files.deleteIfExists(tmp);
            throw new IOException("Backup aborted: could not read " + skipped + " (files busy?). Try again or unload the world first.");
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public List<Path> listBackups(EndSpace space) throws IOException {
        Path dir = backupDir(space);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> BACKUP_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
    }

    public void pruneBackups(EndSpace space, int keep) throws IOException {
        List<Path> all = listBackups(space);
        for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i));
    }

    /** Resolves a backup by file name, refusing anything outside the space's backup directory. */
    public Path resolveBackup(EndSpace space, String fileName) throws IOException {
        if (!BACKUP_NAME.matcher(fileName).matches() || !fileName.startsWith(space.shortId() + "-")) {
            throw new IOException("Invalid backup name");
        }
        Path p = backupDir(space).resolve(fileName).normalize();
        if (!p.startsWith(backupDir(space)) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup not found");
        return p;
    }

    /**
     * Replaces the (unloaded) world folder with the contents of {@code backup}. The current folder is moved to trash
     * first; extraction happens into a staging folder and is swapped in only after it fully succeeded.
     *
     * @return path of the trashed previous folder, or null if there was none
     */
    public Path restore(EndSpace space, Path backup) throws IOException {
        Path target = folderOf(space);
        Path staging = dimensionsRoot().resolve("." + space.shortId() + ".restore");
        deleteTree(staging);
        Files.createDirectories(staging);
        try (InputStream in = Files.newInputStream(backup); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path out = staging.resolve(e.getName()).normalize();
                if (!out.startsWith(staging)) throw new IOException("Blocked unsafe zip entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ex) {
            deleteTree(staging);
            throw ex;
        }
        Path trashed = Files.exists(target) ? moveToTrash(space, "pre-restore") : null;
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        return trashed;
    }

    /** Moves the world folder to the trash directory. Returns the new location (or null if nothing to move). */
    public Path moveToTrash(EndSpace space, String reason) throws IOException {
        Path src = folderOf(space);
        if (!Files.exists(src)) return null;
        Files.createDirectories(trashRoot);
        Path dst = trashRoot.resolve(space.shortId() + "-" + LocalDateTime.now().format(TS) + "-" + reason);
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            copyTree(src, dst);
            deleteTree(src);
        }
        return dst;
    }

    /** Folders under dimensions/endspace that are not known EndSpace ids. */
    public List<String> orphanFolders(java.util.Set<String> knownShortIds) throws IOException {
        Path root = dimensionsRoot();
        if (!Files.isDirectory(root)) return List.of();
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p) && SHORT_ID.matcher(n).matches() && !knownShortIds.contains(n)) out.add(n);
            }
        }
        return out;
    }

    public long sizeOf(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        }
    }
}
