package app.dsandroid.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.dsandroid.BuildConfig;

/**
 * The official DeepSeek engine's app-side helpers: where things live, the API key, the chosen route, and the on-phone
 * tool runs behind harness updates and plugin installs (the bundled node/npm with the engine environment).
 */
public final class Engine {
    private static final String PREFS = "engine";
    public static final String ROUTE_ENGINE = "engine";
    public static final String ROUTE_GOOGLE = "google";

    private Engine() {
    }

    // ------------------------------------------------------------------ layout

    public static File runtime(Context c) {
        return new File(c.getFilesDir(), "runtime");
    }

    public static File home(Context c) {
        return new File(c.getFilesDir(), "home");
    }

    public static File dshHome(Context c) {
        return RuntimeEnv.dshHome(home(c));
    }

    public static File profile(Context c) {
        return new File(new File(dshHome(c), "profiles"), "web");
    }

    public static HarnessUpdater updater(Context c) {
        return new HarnessUpdater(new File(c.getFilesDir(), "engine-updates"), BuildConfig.DSH_VERSION,
                new File(runtime(c), "dsh/node_modules/@deepseek-ai/dsh"));
    }

    public static PluginStore plugins(final Context c) {
        final Context app = c.getApplicationContext();
        return new PluginStore(dshHome(app), profile(app), new File(app.getCacheDir(), "plugins"), new PluginStore.DependencyInstaller() {
            @Override
            public void install(File packageDir) throws IOException {
                Run r = npm(app, packageDir, Arrays.asList("install", "--omit=dev", "--ignore-scripts", "--legacy-peer-deps", "--no-audit", "--no-fund",
                        "--os=android", "--cpu=" + nodeArch(), "--loglevel=error"), TimeUnit.MINUTES.toMillis(10));
                if (r.exit != 0) {
                    throw new IOException("npm exited " + r.exit + ": " + lastLines(r.output, 6));
                }
            }
        });
    }

    /** node's process.arch for this phone: the payload is arm64 or x64. */
    public static String nodeArch() {
        return Build.SUPPORTED_ABIS.length > 0 && Build.SUPPORTED_ABIS[0].startsWith("x86") ? "x64" : "arm64";
    }

    // ------------------------------------------------------------------ key + route

    public static boolean hasKey(Context c) {
        return DshCredentials.hasKey(dshHome(c));
    }

    /** Writes the key into the harness's own credentials store (mode 600); never anywhere else. */
    public static void setKey(Context c, String key) throws IOException {
        DshCredentials.write(dshHome(c), key);
    }

    public static void clearKey(Context c) throws IOException {
        DshCredentials.clear(dshHome(c));
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String route(Context c) {
        return prefs(c).getString("route", "");
    }

    public static void setRoute(Context c, String route) {
        prefs(c).edit().putString("route", route).apply();
    }

    public static boolean showStats(Context c) {
        return prefs(c).getBoolean("show_stats", false);
    }

    public static void setShowStats(Context c, boolean show) {
        prefs(c).edit().putBoolean("show_stats", show).apply();
    }

    public static boolean autoUpdate(Context c) {
        return prefs(c).getBoolean("auto_update", true);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto_update", on).apply();
    }

    /** The daily npm check: true when the last one is more than a day old. */
    public static boolean updateCheckDue(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("update_checked_at", 0) > TimeUnit.DAYS.toMillis(1);
    }

    public static void markUpdateChecked(Context c) {
        prefs(c).edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
    }

    // ------------------------------------------------------------------ harness update

    /** Check npm and, when newer, install + verify the harness; the engine switches on its next restart. */
    public static HarnessUpdater.Result updateHarness(final Context c) {
        final Context app = c.getApplicationContext();
        markUpdateChecked(app);
        HarnessUpdater.Result result = updater(app).update(new HarnessUpdater.Installer() {
            @Override
            public File install(String version, File dir) throws IOException {
                JSONObject deps = new JSONObject();
                try {
                    deps.put("@deepseek-ai/dsh", version);
                    deps.put("dsh-web-mobile", BuildConfig.DSH_WEB_MOBILE_VERSION);
                    String pnpm = bundledDependency(app, "pnpm");
                    if (!pnpm.isEmpty()) {
                        deps.put("pnpm", pnpm);
                    }
                    PluginStore.writeAtomic(new File(dir, "package.json"), new JSONObject().put("name", "deepseek-android-engine-update")
                            .put("private", true).put("version", "1.0.0").put("dependencies", deps).toString(2));
                } catch (org.json.JSONException e) {
                    throw new IOException(e);
                }
                Run r = npm(app, dir, Arrays.asList("install", "--omit=dev", "--ignore-scripts", "--legacy-peer-deps", "--no-audit", "--no-fund",
                        "--os=android", "--cpu=" + nodeArch(), "--loglevel=error"), TimeUnit.MINUTES.toMillis(20));
                if (r.exit != 0) {
                    throw new IOException("npm exited " + r.exit + ": " + lastLines(r.output, 6));
                }
                File wasm = new File(dir, "node_modules/@deepseek-ai/libreoffice-kit-wasm/assets");
                new File(wasm, "soffice.wasm").delete();
                new File(wasm, "soffice.data").delete();
                Run p = patches(app, new File(dir, "node_modules"), false);
                if (p.exit != 0) {
                    throw new IOException("the Android patches do not fit this version: " + lastLines(p.output, 4));
                }
                return HarnessUpdater.dshPackage(dir);
            }
        }, new HarnessUpdater.Verifier() {
            @Override
            public void verify(File dshPackage, String version) throws IOException {
                Run check = patches(app, dshPackage.getParentFile().getParentFile(), true);
                if (check.exit != 0) {
                    throw new IOException("patch check failed: " + lastLines(check.output, 4));
                }
                List<String> cmd = new ArrayList<String>();
                cmd.add(new File(runtime(app), "usr/bin/node").getAbsolutePath());
                cmd.add("--expose-internals");
                cmd.add(new File(dshPackage, "lib/bin.js").getAbsolutePath());
                cmd.add("--version");
                Run v = run(app, cmd, home(app), TimeUnit.MINUTES.toMillis(2));
                if (v.exit != 0 || !v.output.contains(version)) {
                    throw new IOException("dsh --version did not report " + version + ": " + lastLines(v.output, 3));
                }
            }
        });
        EngineLog.append(app, "harness update: " + result.status + " " + (result.version == null ? "" : result.version) + " " + result.message);
        return result;
    }

    static String bundledDependency(Context c, String name) {
        try {
            JSONObject pkg = new JSONObject(NodeServer.readFile(new File(runtime(c), "dsh/package.json")));
            return pkg.getJSONObject("dependencies").optString(name, "");
        } catch (org.json.JSONException e) {
            return "";
        }
    }

    private static Run patches(Context c, File modules, boolean checkOnly) throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(runtime(c), "usr/bin/node").getAbsolutePath());
        cmd.add(new File(runtime(c), "engine/patches/apply-payload-patches.mjs").getAbsolutePath());
        cmd.add(modules.getAbsolutePath());
        cmd.add("--arch");
        cmd.add(nodeArch());
        cmd.add("--rg");
        cmd.add(new File(runtime(c), "usr/bin/rg").getAbsolutePath());
        if (checkOnly) {
            cmd.add("--check");
        }
        return run(c, cmd, home(c), TimeUnit.MINUTES.toMillis(2));
    }

    // ------------------------------------------------------------------ process runs

    public static final class Run {
        public final int exit;
        public final String output;

        Run(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }

    static Run npm(Context c, File cwd, List<String> args, long timeoutMs) throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(runtime(c), "usr/bin/node").getAbsolutePath());
        cmd.add(new File(runtime(c), "usr/lib/node_modules/npm/bin/npm-cli.js").getAbsolutePath());
        cmd.addAll(args);
        return run(c, cmd, cwd, timeoutMs);
    }

    /** Run a bundled tool with the engine environment; output is stdout+stderr, capped at 64 KB. */
    static Run run(Context c, List<String> cmd, File cwd, long timeoutMs) throws IOException {
        File tmp = new File(runtime(c), "tmp");
        if (!tmp.isDirectory() && !tmp.mkdirs()) {
            throw new IOException("cannot create " + tmp);
        }
        if (!new File(cmd.get(0)).isFile()) {
            throw new IOException("the engine is not prepared yet (" + new File(cmd.get(0)).getName() + " missing)");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true);
        RuntimeEnv.apply(pb.environment(), runtime(c), home(c), tmp);
        Process p = pb.start();
        final InputStream in = p.getInputStream();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                int n;
                try {
                    while ((n = in.read(buf)) > 0) {
                        synchronized (out) {
                            if (out.size() < 65536) {
                                out.write(buf, 0, Math.min(n, 65536 - out.size()));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            }
        }, "engine-run");
        pump.start();
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Run(-1, "timed out after " + timeoutMs / 1000 + "s");
            }
            pump.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        synchronized (out) {
            return new Run(p.exitValue(), new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    static String lastLines(String s, int n) {
        String[] lines = s.trim().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            sb.append(lines[i].trim()).append(i < lines.length - 1 ? " / " : "");
        }
        return sb.toString();
    }
}
