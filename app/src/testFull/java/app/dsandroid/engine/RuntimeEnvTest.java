package app.dsandroid.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class RuntimeEnvTest {
    private final File runtime = new File("/data/user/0/app.dsandroid.full/files/runtime");
    private final File home = new File("/data/user/0/app.dsandroid.full/files/home");
    private final File tmp = new File(runtime, "tmp");

    @Test
    public void environmentPointsEverythingAtTheBundledRuntime() {
        Map<String, String> env = new HashMap<String, String>();
        env.put("CLASSPATH", "/system/framework/leak.jar");
        RuntimeEnv.apply(env, runtime, home, tmp);
        assertFalse("the app's own environment is cleared", env.containsKey("CLASSPATH"));
        assertTrue(env.get("PATH").startsWith(runtime + "/usr/bin:"));
        assertEquals(runtime + "/usr/lib", env.get("LD_LIBRARY_PATH"));
        assertEquals(home.getPath(), env.get("HOME"));
        assertEquals(home + "/.dsh", env.get("DSH_HOME"));
        assertEquals(tmp.getPath(), env.get("TMPDIR"));
        assertEquals(runtime + "/usr", env.get("PREFIX"));
        assertEquals(runtime + "/usr/etc/tls/openssl.cnf", env.get("OPENSSL_CONF"));
        assertEquals(runtime + "/usr/etc/tls/cert.pem", env.get("SSL_CERT_FILE"));
        assertEquals(runtime + "/usr/libexec/git-core", env.get("GIT_EXEC_PATH"));
        assertEquals("C.UTF-8", env.get("LANG"));
        assertEquals(runtime + "/usr/bin/bash", env.get("SHELL"));
        assertEquals("danger-full-access", env.get("DSH_PERMISSION_MODE"));
        for (String value : env.values()) {
            assertFalse("no Termux prefix: " + value, value.contains("com.termux"));
            assertFalse("no device token: " + value, value.contains("DEVICE"));
        }
        assertFalse(env.containsKey("DSH_DEVICE_TOKEN"));
    }

    @Test
    public void webCommandRunsTheHarnessOnLoopbackWithNodeInternals() {
        File dsh = new File(runtime, "dsh/node_modules/@deepseek-ai/dsh");
        List<String> cmd = RuntimeEnv.webCommand(runtime, dsh, 3221);
        assertEquals(runtime + "/usr/bin/node", cmd.get(0));
        assertEquals("--expose-internals", cmd.get(1));
        assertEquals(dsh + "/lib/bin.js", cmd.get(2));
        assertEquals(List.of("web", "--port", "3221", "--no-open"), cmd.subList(3, 7));
    }
}
