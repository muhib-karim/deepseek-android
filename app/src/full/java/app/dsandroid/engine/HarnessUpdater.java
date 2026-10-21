package app.dsandroid.engine;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps the official harness current: checks npm for a newer @deepseek-ai/dsh, installs it beside the bundled one and
 * switches to it only after it is verified; any failure leaves the bundled harness in use.
 *
 * Steps for an update (all in app-private storage, filesDir/engine-updates):
 *   1. registry metadata -> dist-tags.latest; nothing happens unless it is newer than the harness in use;
 *   2. the package tarball is downloaded and must match the registry's sha512 integrity;
 *   3. the Installer installs it with the bundled npm into engine-updates/<version> (--omit=dev --ignore-scripts) and
 *      applies the Android payload patches (engine/patches, the same ones CI applies to the bundled payload);
 *   4. the Verifier checks every required patch is in place and `node bin.js --version` reports the version;
 *   5. only then engine-updates/active names it. The engine falls back to the bundled harness when the active one
 *      fails to start (markBad), and the bundled one wins whenever it is newer (an app update).
 */
public final class HarnessUpdater {
    public static final String PACKAGE = "@deepseek-ai/dsh";

    public interface Installer {
        /** Install `version` under dir; return the installed @deepseek-ai/dsh package directory. */
        File install(String version, File dir) throws IOException;
    }

    public interface Verifier {
        void verify(File dshPackage, String version) throws IOException;
    }

    public static final class Check {
        public final String current;
        public final String latest;
        public final String tarball;
        public final String integrity;

        Check(String current, String latest, String tarball, String integrity) {
            this.current = current;
            this.latest = latest;
            this.tarball = tarball;
            this.integrity = integrity;
        }

        public boolean newer() {
            return latest != null && compare(latest, current) > 0;
        }
    }

    public static final class Result {
        public final String status; // up-to-date | updated | failed
        public final String version;
        public final String message;

        Result(String status, String version, String message) {
            this.status = status;
            this.version = version;
            this.message = message;
        }
    }

    private final File updatesDir;
    private final String bundledVersion;
    private final File bundledDsh;
    private String registry = "https://registry.npmjs.org";

    public HarnessUpdater(File updatesDir, String bundledVersion, File bundledDsh) {
        this.updatesDir = updatesDir;
        this.bundledVersion = bundledVersion;
        this.bundledDsh = bundledDsh;
    }

    /** Test seam: a mock registry. */
    public HarnessUpdater registry(String url) {
        this.registry = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return this;
    }

    // ------------------------------------------------------------------ versions

    /** Semver order with prereleases: 0.2.1-rc.2 < 0.2.1-rc.10 < 0.2.1 < 0.2.2-alpha.1. Unparseable sorts lowest. */
    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        String[] pa = split(a);
        String[] pb = split(b);
        int core = compareDots(pa[0], pb[0], true);
        if (core != 0) {
            return core;
        }
        if (pa[1].isEmpty() || pb[1].isEmpty()) {
            return pa[1].isEmpty() ? (pb[1].isEmpty() ? 0 : 1) : -1;
        }
        return compareDots(pa[1], pb[1], false);
    }

    private static String[] split(String v) {
        String s = v.trim();
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        int dash = s.indexOf('-');
        return dash < 0 ? new String[]{s, ""} : new String[]{s.substring(0, dash), s.substring(dash + 1)};
    }

    private static int compareDots(String a, String b, boolean numeric) {
        String[] xa = a.split("\\.");
        String[] xb = b.split("\\.");
        for (int i = 0; i < Math.max(xa.length, xb.length); i++) {
            if (i >= xa.length) {
                return numeric ? compareDots("0", xb[i], true) : -1;
            }
            if (i >= xb.length) {
                return numeric ? compareDots(xa[i], "0", true) : 1;
            }
            boolean na = xa[i].matches("\\d+");
            boolean nb = xb[i].matches("\\d+");
            int c;
            if (na && nb) {
                c = Long.compare(Long.parseLong(xa[i]), Long.parseLong(xb[i]));
            } else if (na != nb) {
                c = na ? -1 : 1; // numeric identifiers sort before alphanumeric ones
            } else {
                c = xa[i].compareTo(xb[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ state

    File activeFile() {
        return new File(updatesDir, "active");
    }

    File badFile() {
        return new File(updatesDir, "bad");
    }

    File dirFor(String version) {
        return new File(updatesDir, version.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** The installed update in use, or null for the bundled harness. */
    public String activeVersion() {
        String v = readQuietly(activeFile());
        if (v.isEmpty() || compare(v, bundledVersion) <= 0 || v.equals(readQuietly(badFile()))) {
            return null;
        }
        File pkg = dshPackage(dirFor(v));
        return new File(pkg, "lib/bin.js").isFile() ? v : null;
    }

    /** The version the engine runs now. */
    public String currentVersion() {
        String v = activeVersion();
        return v == null ? bundledVersion : v;
    }

    /** The @deepseek-ai/dsh package directory to launch. */
    public File activeDsh() {
        String v = activeVersion();
        return v == null ? bundledDsh : dshPackage(dirFor(v));
    }

    /** node_modules holding the active harness and its dependencies. */
    public File activeModules() {
        return activeDsh().getParentFile().getParentFile();
    }

    public static File dshPackage(File installDir) {
        return new File(installDir, "node_modules/@deepseek-ai/dsh");
    }

    /** The active update did not start: never use that version again; the bundled harness takes over. */
    public void markBad() throws IOException {
        String v = activeVersion();
        if (v != null) {
            write(badFile(), v);
            write(activeFile(), "");
        }
    }

    // ------------------------------------------------------------------ check + update

    public Check check() throws IOException {
        String body = PluginStore.httpText(registry + "/" + "@deepseek-ai%2Fdsh");
        try {
            JSONObject meta = new JSONObject(body);
            JSONObject tags = meta.getJSONObject("dist-tags");
            String latest = tags.getString("latest");
            JSONObject version = meta.getJSONObject("versions").getJSONObject(latest);
            JSONObject dist = version.getJSONObject("dist");
            return new Check(currentVersion(), latest, dist.getString("tarball"), dist.optString("integrity", ""));
        } catch (JSONException e) {
            throw new IOException("unexpected registry answer: " + e.getMessage());
        }
    }

    public Result update(Installer installer, Verifier verifier) {
        Check check;
        try {
            check = check();
        } catch (IOException e) {
            return new Result("failed", null, "Could not reach the npm registry: " + e.getMessage());
        }
        if (!check.newer()) {
            return new Result("up-to-date", check.current, "DeepSeek Harness " + check.current + " is the newest.");
        }
        String v = check.latest;
        if (v.equals(readQuietly(badFile()))) {
            return new Result("failed", v, "DeepSeek Harness " + v + " did not start on this phone before; staying on " + check.current + ".");
        }
        if (!check.integrity.startsWith("sha512-")) {
            return new Result("failed", v, "The registry gave no sha512 checksum for " + v + "; not installing it.");
        }
        File staging = new File(updatesDir, ".staging-" + System.nanoTime());
        File target = dirFor(v);
        try {
            if (!staging.mkdirs()) {
                throw new IOException("cannot create " + staging);
            }
            try {
                PluginStore.download(check.tarball, new File(staging, "dsh.tgz"), check.integrity);
            } catch (PluginStore.PluginException e) {
                throw new IOException(e.getMessage());
            }
            File pkg = installer.install(v, staging);
            verifier.verify(pkg, v);
            Files.deleteIfExists(new File(staging, "dsh.tgz").toPath());
            PluginStore.deleteTree(target);
            if (!staging.renameTo(target)) {
                throw new IOException("cannot move the update into place");
            }
            write(activeFile(), v);
            removeOthers(v);
            return new Result("updated", v, "DeepSeek Harness " + v + " is installed; it starts with the next engine restart.");
        } catch (IOException e) {
            return new Result("failed", v, "DeepSeek Harness " + v + " could not be installed (" + e.getMessage()
                    + "); still using " + check.current + ".");
        } finally {
            PluginStore.deleteTree(staging);
        }
    }

    /** Keep only the active update (and nothing half-written). */
    private void removeOthers(String keep) {
        File[] kids = updatesDir.listFiles();
        if (kids == null) {
            return;
        }
        File keepDir = dirFor(keep);
        for (File kid : kids) {
            if (kid.isDirectory() && !kid.equals(keepDir)) {
                PluginStore.deleteTree(kid);
            }
        }
    }

    private static String readQuietly(File f) {
        try {
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File f, String s) throws IOException {
        PluginStore.writeAtomic(f, s + "\n");
    }
}
