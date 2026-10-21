/*
 * Ported from dsh-android (https://github.com/yhm080315-sudo/dsh-android), commit d799c76,
 * app/src/com/dsh/android/NodeServer.java.
 * Changes for DeepSeek Android: payload streamed from the APK's lib/<abi>/libdshpayload.so with extraction progress;
 * environment and command from RuntimeEnv (node --expose-internals); harness updates (HarnessUpdater) with fallback to
 * the bundled harness; no device bridge, root, busybox, launcher console or diagnostics HTTP server; no state, plugin or
 * credentials mirror to /sdcard; no skin, boot-animation, splash or screen-operate rows (dsh-web-mobile only); harness
 * adoption across service instances replaced by stopping a leftover harness recorded in a pid file; the page check
 * (full UI vs the 3709-byte empty plugin graph) runs here and gates "ready".
 *
 * MIT License
 *
 * Copyright (c) 2026 DSH for Android contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.dsandroid.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * Owns the bundled Node process that runs DeepSeek's harness (`dsh web`), and the runtime extraction that has to happen
 * before it can start. Everything lives under filesDir: runtime/ (replaced wholesale when the payload changes) and home/
 * (the harness home, sessions, credentials, plugins: never touched by an update).
 */
public class NodeServer extends Service {
    private static final String TAG = "DshEngine";
    private static final String CHANNEL_ID = "dsh-engine";
    private static final int NOTIFICATION_ID = 1301;
    public static final String ACTION_START = "app.dsandroid.engine.START";
    public static final String ACTION_STOP = "app.dsandroid.engine.STOP";
    public static final String ACTION_RESTART = "app.dsandroid.engine.RESTART";
    private static final int MAX_RESTARTS = 3;
    /** A full UI page is ~35 KB; an empty client plugin graph is 3709 bytes (dsh-android PITFALLS #8). */
    static final int FULL_UI_MIN_BYTES = 20000;
    private static final long PAGE_WAIT_MS = 120000;

    public enum State { IDLE, PREPARING, STARTING, READY, FAILED, STOPPED }

    public interface Listener {
        void onState(State state, int progress, String detail);

        void onReady(String url);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static volatile State sState = State.IDLE;
    private static volatile int sProgress;
    private static volatile String sDetail = "";
    private static volatile String sUrl;
    private static volatile int sPageBytes;

    /** Set only when the app asked for a stop: the exit code cannot tell (SIGTERM reports a clean 0). */
    private volatile boolean stopRequested;
    private volatile boolean restartRequested;
    private Process process;
    private Thread worker;
    private int restarts;
    private int port = PortPicker.START;
    private PowerManager.WakeLock wakeLock;

    public static State state() {
        return sState;
    }

    public static int progress() {
        return sProgress;
    }

    public static String detail() {
        return sDetail;
    }

    public static String url() {
        return sUrl;
    }

    public static int pageBytes() {
        return sPageBytes;
    }

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void start(Context context) {
        send(context, ACTION_START);
    }

    public static void restart(Context context) {
        send(context, ACTION_RESTART);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, NodeServer.class).setAction(ACTION_STOP);
        context.startService(i);
    }

    private static void send(Context context, String action) {
        Intent i = new Intent(context, NodeServer.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    private void setState(State state, int progress, String detail) {
        sState = state;
        sProgress = progress;
        sDetail = detail == null ? "" : detail;
        for (Listener l : LISTENERS) {
            l.onState(state, progress, sDetail);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        startForeground(NOTIFICATION_ID, buildNotification("Starting the DeepSeek engine…"));
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            stopHarness();
            setState(State.STOPPED, 0, "Stopped");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action) && stillRunning(process)) {
            restartRequested = true;
            restarts = 0;
            stopHarness(); // the waiter thread starts the next one
            return START_STICKY;
        }
        stopRequested = false;
        if ((worker == null || !worker.isAlive()) && !stillRunning(process)) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        prepare();
                        launch();
                    } catch (Throwable t) {
                        Log.e(TAG, "startup failed", t);
                        EngineLog.append(NodeServer.this, "STARTUP FAILED: " + t);
                        setState(State.FAILED, 0, String.valueOf(t.getMessage()));
                        postNotification("The DeepSeek engine could not start");
                    }
                }
            }, "dsh-start");
            worker.start();
        } else if (sState == State.READY && sUrl != null) {
            for (Listener l : LISTENERS) {
                l.onReady(sUrl);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        stopHarness();
        super.onDestroy();
    }

    private void stopHarness() {
        Process p = process;
        if (p != null) {
            p.destroy(); // SIGTERM: the harness flushes its session logs and exits 0 (payload patch P17b logs it)
            try {
                if (Build.VERSION.SDK_INT >= 26 && !p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        sUrl = null;
    }

    // ---------------------------------------------------------------- layout

    File runtimeDir() {
        return new File(getFilesDir(), "runtime");
    }

    File homeDir() {
        return new File(getFilesDir(), "home");
    }

    File dshHome() {
        return RuntimeEnv.dshHome(homeDir());
    }

    File profileDir() {
        return new File(new File(dshHome(), "profiles"), "web");
    }

    private File marker() {
        return new File(runtimeDir(), ".extracted");
    }

    private File pidFile() {
        return new File(getFilesDir(), "engine.pid");
    }

    HarnessUpdater updater() {
        return Engine.updater(this);
    }

    // ---------------------------------------------------------------- extraction

    private void prepare() throws IOException {
        File rt = runtimeDir();
        PayloadSource payload = PayloadSource.find(this);
        if (payload == null) {
            throw new IOException("This build has no engine payload (lib/<abi>/" + PayloadSource.NAME + "); install a release build.");
        }
        String have = marker().isFile() ? readFile(marker()).trim() : "";
        if (payload.stamp.equals(have) && new File(rt, "usr/bin/node").isFile()) {
            EngineLog.append(this, "runtime already extracted (" + have + ")");
            return;
        }
        setState(State.PREPARING, 0, "Preparing the engine");
        EngineLog.append(this, "extracting runtime from " + payload.description + " into an empty tree");
        // Always from an empty tree: a checkpoint belongs to one archive, and a tree the app cannot overwrite would stop
        // the extractor on the same file every time. Renaming the old tree aside always succeeds (dsh-android).
        wipeRuntimeTree(rt);
        if (!rt.mkdirs() && !rt.isDirectory()) {
            throw new IOException("cannot create " + rt);
        }
        final long total = Math.max(1, payload.compressedSize);
        InputStream raw = payload.open();
        final TarExtractor.Counting compressed = new TarExtractor.Counting(raw);
        long t0 = System.currentTimeMillis();
        final int[] entries = {0};
        final int[] lastPct = {-1};
        try {
            TarExtractor.extract(new GZIPInputStream(compressed, 1 << 16), rt, new TarExtractor.Progress() {
                @Override
                public void onEntry(String name) {
                    entries[0]++;
                    int pct = (int) Math.min(99, compressed.count * 100 / total);
                    if (pct != lastPct[0]) {
                        lastPct[0] = pct;
                        setState(State.PREPARING, pct, "Preparing the engine");
                        if (pct % 10 == 0) {
                            postNotification("Preparing the DeepSeek engine… " + pct + "%");
                        }
                    }
                }
            });
        } finally {
            compressed.close();
        }
        EngineLog.append(this, "extracted " + entries[0] + " entries in " + (System.currentTimeMillis() - t0) / 1000 + "s");
        makeExecutable(new File(rt, "usr/bin"));
        makeExecutable(new File(rt, "usr/lib"));
        makeExecutable(new File(rt, "usr/libexec"));
        writeFile(marker(), payload.stamp);
        setState(State.PREPARING, 100, "Engine ready to start");
    }

    /** Park the old tree (rename needs only the parent), then delete what can be deleted, in the background. */
    private void wipeRuntimeTree(File rt) {
        File parent = rt.getParentFile();
        File[] old = parent == null ? null : parent.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.getName().startsWith("runtime.stale-")) {
                    PluginStore.deleteTree(f);
                }
            }
        }
        if (!rt.exists()) {
            return;
        }
        final File parked = new File(parent, "runtime.stale-" + System.currentTimeMillis());
        if (rt.renameTo(parked)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    PluginStore.deleteTree(parked);
                }
            }, "dsh-wipe").start();
        } else {
            PluginStore.deleteTree(rt);
        }
    }

    private static void makeExecutable(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (Files.isSymbolicLink(f.toPath())) {
                continue;
            }
            if (f.isDirectory()) {
                makeExecutable(f);
            } else {
                f.setReadable(true, true);
                f.setExecutable(true, true);
            }
        }
    }

    // ---------------------------------------------------------------- profile

    /** Profile rows the app writes. dsh-web-mobile is the harness's phone layout (MIT); HMR needs internals flags. */
    private static final String[][] CLIENT_ROWS = {{"dsh-web-mobile", "dsh-web-mobile"}};
    private static final String[] DISABLED_ROWS = {"hmr"};

    /**
     * The profile gets a real node_modules directory with symlinks to the harness's own packages (dsh-android
     * PITFALLS #8: without it the client plugin graph is empty and the page is 3709 bytes). Links are re-pointed on every
     * start, so a harness update or a new payload is picked up; real directories (user plugins) are left alone.
     */
    void prepareProfile() throws IOException {
        File web = profileDir();
        File modules = new File(web, "node_modules");
        if (Files.isSymbolicLink(modules.toPath())) {
            Files.delete(modules.toPath());
        }
        if (!modules.isDirectory() && !modules.mkdirs()) {
            throw new IOException("profile node_modules unavailable: " + modules);
        }
        File bundled = updater().activeModules();
        int linked = 0;
        File[] entries = bundled.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.getName().startsWith(".")) {
                    continue;
                }
                if (entry.getName().startsWith("@") && entry.isDirectory()) {
                    // Link scoped packages one by one so a user plugin can live in the same scope.
                    File scope = new File(modules, entry.getName());
                    if (Files.isSymbolicLink(scope.toPath())) {
                        Files.delete(scope.toPath());
                    }
                    if (!scope.isDirectory() && !scope.mkdirs()) {
                        continue;
                    }
                    File[] scoped = entry.listFiles();
                    if (scoped != null) {
                        for (File pkg : scoped) {
                            linked += link(pkg, new File(scope, pkg.getName()));
                        }
                    }
                } else {
                    linked += link(entry, new File(modules, entry.getName()));
                }
            }
        }
        linked += linkNested(bundled, modules, 0);
        EngineLog.append(this, "profile node_modules: " + linked + " bundled packages linked from " + bundled);
        ensurePluginRows(web, modules);
        DshCredentials.ensure(dshHome());
        writeAgentInstructions();
    }

    /**
     * npm nests a package when versions conflict; the loader resolves plugin names from the profile, so a package that
     * exists only nested gets a profile link too (the first copy found; top-level copies always win).
     */
    private static int linkNested(File modules, File view, int depth) {
        if (depth > 6) {
            return 0;
        }
        int linked = 0;
        File[] entries = modules.listFiles();
        if (entries == null) {
            return 0;
        }
        for (File entry : entries) {
            File[] pkgs = entry.getName().startsWith("@") ? entry.listFiles() : new File[]{entry};
            if (pkgs == null || entry.getName().startsWith(".")) {
                continue;
            }
            for (File pkg : pkgs) {
                File nested = new File(pkg, "node_modules");
                if (!nested.isDirectory()) {
                    continue;
                }
                File[] inner = nested.listFiles();
                if (inner != null) {
                    for (File child : inner) {
                        File[] innerPkgs = child.getName().startsWith("@") ? child.listFiles() : new File[]{child};
                        if (innerPkgs == null || child.getName().startsWith(".")) {
                            continue;
                        }
                        for (File p : innerPkgs) {
                            String name = child.getName().startsWith("@") ? child.getName() + "/" + p.getName() : p.getName();
                            File target = new File(view, name);
                            if (!target.exists() && !Files.isSymbolicLink(target.toPath()) && new File(p, "package.json").isFile()) {
                                File parent = target.getParentFile();
                                if (parent.isDirectory() || parent.mkdirs()) {
                                    linked += link(p, target);
                                }
                            }
                        }
                    }
                }
                linked += linkNested(nested, view, depth + 1);
            }
        }
        return linked;
    }

    private static int link(File target, File view) {
        try {
            if (Files.isSymbolicLink(view.toPath())) {
                if (Files.readSymbolicLink(view.toPath()).toString().equals(target.getAbsolutePath())) {
                    return 1;
                }
                Files.delete(view.toPath());
            } else if (view.exists()) {
                return 0; // a real directory: a user plugin, kept
            }
            Files.createSymbolicLink(view.toPath(), target.toPath().toAbsolutePath());
            return 1;
        } catch (IOException e) {
            return 0;
        }
    }

    private void ensurePluginRows(File web, File modules) {
        File patch = new File(web, "cordis.patch.yml");
        try {
            String existing = patch.isFile() ? readFile(patch) : "";
            StringBuilder rows = new StringBuilder();
            for (String[] row : CLIENT_ROWS) {
                if (existing.contains("name: '" + row[1] + "'") || !new File(modules, row[1]).isDirectory()) {
                    continue;
                }
                rows.append("- insert:\n    - id: ").append(row[0]).append("\n      name: '").append(row[1]).append("'\n");
            }
            StringBuilder off = new StringBuilder();
            for (String id : DISABLED_ROWS) {
                if (!existing.contains("- id: " + id + "\n")) {
                    off.append("- id: ").append(id).append("\n  disabled: true\n");
                }
            }
            if (rows.length() == 0 && off.length() == 0) {
                return;
            }
            boolean emptyList = true;
            for (String line : existing.split("\n")) {
                String t = line.trim();
                if (!(t.isEmpty() || t.startsWith("#") || t.equals("[]"))) {
                    emptyList = false;
                    break;
                }
            }
            String body = emptyList
                    ? "# Android: profile rows added by DeepSeek Android\n" + rows + off
                    : existing + (existing.endsWith("\n") ? "" : "\n") + rows + off;
            PluginStore.writeAtomic(patch, body);
            EngineLog.append(this, "profile patch: rows written");
        } catch (IOException t) {
            EngineLog.append(this, "plugin rows failed: " + t);
        }
    }

    /** User-level agent instructions ($DSH_HOME/AGENTS.md): what this phone has and has not (dsh-android, rewritten). */
    private void writeAgentInstructions() {
        String body = "# Android environment (written by DeepSeek Android)\n\n"
                + "You run inside the DeepSeek Android app on a phone, not on a desktop.\n\n"
                + "- Workspace and working directory: `" + workspaceDir().getAbsolutePath() + "` (app-private storage).\n"
                + "- Shell: `bash` with GNU coreutils, grep, sed, findutils, gawk, tar, gzip, `rg`, `git`, `node`, `npm` and `pnpm`.\n"
                + "- There is no root, no sudo, no apt/pkg, no python and no compilers. Do not try `su`.\n"
                + "- Terminals are not interactive: run one-shot commands, never a REPL or an editor.\n"
                + "- Files you make are shown in the chat; the person can share or save them from there.\n"
                + "- The person is on a phone screen: keep answers short and put long output in files.\n";
        File target = new File(dshHome(), "AGENTS.md");
        try {
            if (!target.isFile() || !readFile(target).equals(body)) {
                PluginStore.writeAtomic(target, body);
            }
        } catch (IOException e) {
            EngineLog.append(this, "agent instructions not written: " + e);
        }
    }

    File workspaceDir() {
        return new File(homeDir(), "workspace");
    }

    // ---------------------------------------------------------------- launch

    private void launch() throws IOException {
        File rt = runtimeDir();
        File node = new File(rt, "usr/bin/node");
        if (!node.isFile()) {
            throw new IOException("node binary missing at " + node);
        }
        stopLeftoverHarness();
        File home = homeDir();
        File tmp = new File(rt, "tmp");
        File workspace = workspaceDir();
        for (File d : new File[]{home, tmp, workspace, dshHome()}) {
            if (!d.isDirectory() && !d.mkdirs()) {
                throw new IOException("cannot create " + d);
            }
        }
        prepareProfile();
        port = PortPicker.pick(PortPicker.START, PortPicker.SPAN);
        if (port < 0) {
            throw new IOException("no free loopback port from " + PortPicker.START);
        }
        final HarnessUpdater updater = updater();
        final String version = updater.currentVersion();
        final boolean onUpdate = updater.activeVersion() != null;
        List<String> cmd = RuntimeEnv.webCommand(rt, updater.activeDsh(), port);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workspace);
        Map<String, String> env = pb.environment();
        RuntimeEnv.apply(env, rt, home, tmp);
        setState(State.STARTING, 0, "Starting DeepSeek Harness " + version);
        EngineLog.append(this, "launching dsh " + version + " web --port " + port + (onUpdate ? " (installed update)" : " (bundled)"));
        sUrl = null;
        sPageBytes = 0;
        process = pb.start();
        writePid(process);
        final Process proc = process;
        startPump(proc.getInputStream(), false);
        startPump(proc.getErrorStream(), true);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeepSeekAndroid:engine");
                wakeLock.setReferenceCounted(false);
            }
            // A turn keeps running with the screen off; bounded so a forgotten engine cannot hold the CPU forever.
            wakeLock.acquire(2 * 60 * 60 * 1000L);
        }
        postNotification("DeepSeek engine is running");
        final long startedAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int code = 0;
                try {
                    code = proc.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                long lived = System.currentTimeMillis() - startedAt;
                EngineLog.append(NodeServer.this, "harness exited (" + exitReason(code) + ") after " + lived / 1000 + "s");
                if (process == proc) {
                    process = null;
                }
                sUrl = null;
                if (stopRequested) {
                    return;
                }
                if (restartRequested) {
                    restartRequested = false;
                    relaunch();
                    return;
                }
                if (lived < 10000 && onUpdate) {
                    // An installed update that dies on takeoff is not used again: back to the bundled harness.
                    try {
                        updater.markBad();
                    } catch (IOException ignored) {
                    }
                    EngineLog.append(NodeServer.this, "harness " + version + " failed to start; falling back to the bundled harness");
                    relaunch();
                    return;
                }
                if (lived > 10000 && restarts < MAX_RESTARTS) {
                    restarts++;
                    EngineLog.append(NodeServer.this, "restart " + restarts + "/" + MAX_RESTARTS);
                    sleepQuietly(3000);
                    relaunch();
                    return;
                }
                if (lived <= 10000) {
                    for (RuntimeProbe.Step step : new RuntimeProbe(runtimeDir(), homeDir()).runAll()) {
                        EngineLog.append(NodeServer.this, step.render().trim());
                    }
                }
                setState(State.FAILED, 0, "The engine stopped (" + exitReason(code) + "). Open Engine status for the log.");
                postNotification("The DeepSeek engine stopped");
            }
        }, "dsh-wait").start();
    }

    private void relaunch() {
        try {
            launch();
        } catch (Throwable t) {
            EngineLog.append(this, "restart failed: " + t);
            setState(State.FAILED, 0, String.valueOf(t.getMessage()));
        }
    }

    /** A harness left by a previous app process would hold the session logs: stop it (pid recorded at launch). */
    private void stopLeftoverHarness() {
        File f = pidFile();
        if (!f.isFile()) {
            return;
        }
        try {
            int pid = Integer.parseInt(readFile(f).trim());
            String cmdline = readFile(new File("/proc/" + pid + "/cmdline")).replace('\0', ' ');
            if (cmdline.contains("@deepseek-ai/dsh/lib/bin.js") && cmdline.contains(" web ")) {
                EngineLog.append(this, "stopping a leftover harness (pid " + pid + ")");
                android.os.Process.sendSignal(pid, 15);
                for (int i = 0; i < 30 && new File("/proc/" + pid).exists(); i++) {
                    sleepQuietly(100);
                }
                if (new File("/proc/" + pid).exists()) {
                    android.os.Process.sendSignal(pid, 9);
                }
            }
        } catch (NumberFormatException ignored) {
            // not a pid
        }
        f.delete();
    }

    private void writePid(Process p) {
        try {
            java.lang.reflect.Field field = p.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            writeFile(pidFile(), String.valueOf(field.getInt(p)));
        } catch (Throwable ignored) {
            // best effort: without it a leftover harness is found by its port band only
        }
    }

    private void startPump(final InputStream stream, final boolean isErr) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 4096);
                    String line;
                    while ((line = r.readLine()) != null) {
                        String url = extractUrl(line);
                        // The token is a session secret: the log keeps the line without it.
                        EngineLog.append(NodeServer.this, (isErr ? "! " : "  ") + (url == null ? line : line.replace(url, redact(url))));
                        if (url != null && portOf(url) > 0) {
                            final String found = url;
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    awaitFullUi(found);
                                }
                            }, "dsh-page-check").start();
                        }
                    }
                } catch (IOException ignored) {
                    // the process ended; the waiter reports it
                }
            }
        }, isErr ? "dsh-err" : "dsh-out");
        t.setDaemon(true);
        t.start();
    }

    /** Ready means the full UI: poll until the page carries the plugin graph, not the 3709-byte empty shell. */
    private void awaitFullUi(String url) {
        long deadline = System.currentTimeMillis() + PAGE_WAIT_MS;
        int bytes = 0;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            bytes = pageSize(url);
            if (bytes >= FULL_UI_MIN_BYTES) {
                sPageBytes = bytes;
                sUrl = url;
                restarts = 0;
                EngineLog.append(this, "page check: " + bytes + " bytes (full UI)");
                setState(State.READY, 100, "Ready");
                for (Listener l : LISTENERS) {
                    l.onReady(url);
                }
                return;
            }
            sleepQuietly(500);
        }
        sPageBytes = bytes;
        EngineLog.append(this, "page check: " + bytes + " bytes after " + PAGE_WAIT_MS / 1000 + "s (no full UI)");
        if (!stopRequested) {
            setState(State.FAILED, 0, "The engine started but its page is incomplete (" + bytes + " bytes).");
        }
    }

    static int pageSize(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) {
                return 0;
            }
            return PluginStore.readAll(c.getInputStream(), 16L * 1024 * 1024).length;
        } catch (IOException e) {
            return 0;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Pull http://127.0.0.1:PORT/... (with its ?token=) out of the banner the harness prints. */
    static String extractUrl(String line) {
        int i = line.indexOf("http://127.0.0.1:");
        if (i < 0) {
            return null;
        }
        int end = i;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        String url = line.substring(i, end);
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(")")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    static int portOf(String url) {
        String prefix = "http://127.0.0.1:";
        if (!url.startsWith(prefix)) {
            return -1;
        }
        int i = prefix.length();
        int end = i;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        try {
            return end == i ? -1 : Integer.parseInt(url.substring(i, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String redact(String url) {
        int at = url.indexOf("token=");
        return at < 0 ? url : url.substring(0, at) + "token=…";
    }

    static String exitReason(int code) {
        if (code > 128 && code < 160) {
            String[] names = {"HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE", "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM"};
            int n = code - 128;
            return "code " + code + ", killed by " + (n >= 1 && n <= names.length ? "SIG" + names[n - 1] : "signal " + n);
        }
        return code == 0 ? "code 0, clean exit" : "code " + code;
    }

    private static boolean stillRunning(Process p) {
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- plumbing

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "DeepSeek engine", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while DeepSeek's harness runs on this phone");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent open = new Intent(this, EngineActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NodeServer.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return b.setContentTitle("DeepSeek")
                .setContentText(text)
                .setSmallIcon(app.dsandroid.R.drawable.ic_mark)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void postNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    static String readFile(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeFile(File f, String content) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }
}
