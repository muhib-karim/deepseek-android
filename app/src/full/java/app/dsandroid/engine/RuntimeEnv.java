package app.dsandroid.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The environment and command line every child of the engine gets: the bundled bionic runtime, its TLS store and the
 * harness home. Pure Java so it is unit-tested on the JVM.
 *
 * Adapted from dsh-android's NodeServer.applyRuntimeEnv (MIT, Copyright (c) 2026 DSH for Android contributors,
 * https://github.com/yhm080315-sudo/dsh-android, commit d799c76; licence text in THIRD-PARTY.md). Changes: no device
 * bridge token (no root device control in this app), git and npm variables for the bundled tools, the launch command.
 *
 * Layout under the runtime root (extracted from the payload): usr/{bin,lib,libexec,etc/tls,share}, dsh/node_modules.
 */
public final class RuntimeEnv {
    /** The harness's credentials provider reads $DSH_HOME/.credentials.yaml; DSH_HOME is always home/.dsh. */
    public static final String DSH_HOME_DIR = ".dsh";

    private RuntimeEnv() {
    }

    public static File prefix(File runtime) {
        return new File(runtime, "usr");
    }

    public static File dshHome(File home) {
        return new File(home, DSH_HOME_DIR);
    }

    /** Fill env (cleared first, so nothing from the app process leaks into the harness). */
    public static void apply(Map<String, String> env, File runtime, File home, File tmp) {
        File prefix = prefix(runtime);
        File bin = new File(prefix, "bin");
        File tls = new File(prefix, "etc/tls");
        env.clear();
        env.put("PATH", bin.getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        // Keep the harness's state inside the app's own tree.
        env.put("DSH_HOME", dshHome(home).getAbsolutePath());
        /*
         * The harness sandboxes its own child processes with bwrap or Landlock, neither of which exists for Android, and
         * its sandbox service fails closed when the chain is empty: every shell tool call would be refused. Android's
         * app sandbox is the boundary that remains (dsh-android, same reasoning).
         */
        env.put("DSH_PERMISSION_MODE", "danger-full-access");
        env.put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("TERMINFO", new File(prefix, "share/terminfo").getAbsolutePath());
        env.put("SHELL", new File(bin, "bash").getAbsolutePath());
        // Termux binaries have /data/data/com.termux/... compiled in: point OpenSSL, curl and git at bundled copies.
        env.put("OPENSSL_CONF", new File(tls, "openssl.cnf").getAbsolutePath());
        env.put("SSL_CERT_FILE", new File(tls, "cert.pem").getAbsolutePath());
        env.put("SSL_CERT_DIR", tls.getAbsolutePath());
        env.put("CURL_CA_BUNDLE", new File(tls, "cert.pem").getAbsolutePath());
        env.put("GIT_SSL_CAINFO", new File(tls, "cert.pem").getAbsolutePath());
        env.put("GIT_EXEC_PATH", new File(prefix, "libexec/git-core").getAbsolutePath());
        env.put("GIT_TEMPLATE_DIR", new File(prefix, "share/git-core/templates").getAbsolutePath());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_PAGER", "cat");
        env.put("PAGER", "cat");
        env.put("npm_config_cache", new File(tmp, "npm-cache").getAbsolutePath());
        env.put("npm_config_update_notifier", "false");
        env.put("NODE_ENV", "production");
    }

    /**
     * The harness launch: `node --expose-internals <dsh>/lib/bin.js web --port N --no-open`. --expose-internals gives
     * dsh-app-boot Node's module internals, which on desktop come from a native addon that has no Android build
     * (payload patch P1).
     */
    public static List<String> webCommand(File runtime, File dshPackage, int port) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(prefix(runtime), "bin/node").getAbsolutePath());
        cmd.add("--expose-internals");
        cmd.add(new File(dshPackage, "lib/bin.js").getAbsolutePath());
        cmd.add("web");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--no-open");
        return cmd;
    }
}
