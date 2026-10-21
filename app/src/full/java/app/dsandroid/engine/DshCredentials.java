package app.dsandroid.engine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The DeepSeek API key, written only where the harness reads it: $DSH_HOME/.credentials.yaml in app-private storage,
 * owner-only (mode 600; @deepseek-ai/dsh-credentials-local refuses a store readable beyond its owner).
 *
 * Format (dsh-credentials-local 0.2.x): a `version: 1` document whose `refs` map credential references to strings.
 * The DeepSeek API-key provider (dsh-llm-deepseek-api-key) resolves the reference DEEPSEEK_API_KEY. Other refs the
 * harness's own Models page wrote are kept line for line. The key is never logged, never copied to shared storage.
 */
public final class DshCredentials {
    public static final String FILE_NAME = ".credentials.yaml";
    public static final String REF = "DEEPSEEK_API_KEY";

    private DshCredentials() {
    }

    public static File file(File dshHome) {
        return new File(dshHome, FILE_NAME);
    }

    /** Why a key is not usable, or null. Only the shape is checked here; DeepSeek checks the key itself on first use. */
    public static String problem(String key) {
        if (key == null || key.trim().isEmpty()) {
            return "Enter your DeepSeek API key.";
        }
        String k = key.trim();
        if (k.length() < 8 || k.length() > 512) {
            return "That doesn't look like a DeepSeek API key.";
        }
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c <= ' ' || c == '"' || c == '\\' || c > '~') {
                return "An API key has no spaces, quotes or line breaks.";
            }
        }
        return null;
    }

    /** Write (or replace) the key; creates the store with an empty `refs` map when it does not exist yet. */
    public static void write(File dshHome, String key) throws IOException {
        String p = problem(key);
        if (p != null) {
            throw new IllegalArgumentException(p);
        }
        update(dshHome, "  " + REF + ": \"" + key.trim() + "\"");
    }

    /** Remove the key (other refs stay). */
    public static void clear(File dshHome) throws IOException {
        update(dshHome, null);
    }

    /** Ensure the store exists in the current layout and is owner-only, without changing any value. */
    public static void ensure(File dshHome) throws IOException {
        File f = file(dshHome);
        if (!f.isFile()) {
            update(dshHome, null);
        } else {
            ownerOnly(f);
        }
    }

    public static boolean hasKey(File dshHome) {
        File f = file(dshHome);
        if (!f.isFile()) {
            return false;
        }
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1)) {
                if (isRefLine(line)) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** Rewrite the document with our ref line replaced (or removed when refLine is null); other lines are kept. */
    static String render(String existing, String refLine) {
        List<String> out = new ArrayList<String>();
        boolean sawVersion = false;
        boolean sawRefs = false;
        boolean inRefs = false;
        int refsAt = -1;
        if (existing != null && !existing.trim().isEmpty() && !existing.trim().equals("{}")) {
            for (String line : existing.split("\n", -1)) {
                String t = line.trim();
                boolean topLevel = !line.startsWith(" ") && !line.startsWith("\t") && !t.isEmpty() && !t.startsWith("#");
                if (topLevel) {
                    inRefs = false;
                }
                if (topLevel && t.startsWith("version:")) {
                    sawVersion = true;
                }
                if (topLevel && (t.equals("refs:") || t.equals("refs: {}"))) {
                    sawRefs = true;
                    inRefs = true;
                    out.add("refs:");
                    refsAt = out.size();
                    continue;
                }
                if (inRefs && isRefLine(line)) {
                    continue;
                }
                out.add(line);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        if (!sawVersion) {
            out.add(0, "version: 1");
            if (refsAt >= 0) {
                refsAt++;
            }
        }
        if (!sawRefs) {
            out.add("refs:");
            refsAt = out.size();
        }
        if (refLine != null) {
            out.add(refsAt, refLine);
        } else if (refsAt == out.size() || (refsAt < out.size() && !out.get(refsAt).startsWith(" "))) {
            // An empty map must be written as {} for the YAML to read as a mapping, not null.
            out.set(refsAt - 1, "refs: {}");
        }
        StringBuilder sb = new StringBuilder();
        for (String line : out) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static boolean isRefLine(String line) {
        String t = line.trim();
        return (line.startsWith(" ") || line.startsWith("\t"))
                && (t.startsWith(REF + ":") || t.startsWith("\"" + REF + "\":") || t.startsWith("'" + REF + "':"));
    }

    private static synchronized void update(File dshHome, String refLine) throws IOException {
        if (!dshHome.isDirectory() && !dshHome.mkdirs() && !dshHome.isDirectory()) {
            throw new IOException("cannot create " + dshHome);
        }
        File f = file(dshHome);
        String existing = f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        String body = render(existing, refLine);
        File tmp = new File(dshHome, FILE_NAME + ".tmp-" + tmpSuffix());
        Files.deleteIfExists(tmp.toPath());
        Files.createFile(tmp.toPath());
        ownerOnly(tmp);
        Files.write(tmp.toPath(), body.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        ownerOnly(f);
    }

    /** rw------- : the store must not be group- or world-readable (Android's default file mode is 660). */
    static void ownerOnly(File f) throws IOException {
        try {
            Files.setPosixFilePermissions(f.toPath(), EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setExecutable(false, false);
            if (!f.setReadable(true, true) || !f.setWritable(true, true)) {
                throw new IOException("cannot make " + f + " owner-only");
            }
        }
    }

    private static long tmpSuffix() {
        return Thread.currentThread().getId() ^ System.nanoTime();
    }
}
