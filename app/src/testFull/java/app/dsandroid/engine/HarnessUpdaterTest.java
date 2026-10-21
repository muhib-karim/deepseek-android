package app.dsandroid.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The in-app harness update path against a mocked npm registry. */
public class HarnessUpdaterTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private MockWebServer registry;
    private File updates;
    private File bundled;
    private volatile String latest = "0.2.2";
    private volatile boolean tamper;
    private final byte[] tarball = "pretend tarball".getBytes();

    @Before
    public void setUp() throws Exception {
        updates = tmp.newFolder("engine-updates");
        bundled = tmp.newFolder("runtime", "dsh", "node_modules", "@deepseek-ai", "dsh");
        registry = new MockWebServer();
        registry.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    String base = registry.url("/").toString().replaceAll("/$", "");
                    if (request.getPath().equals("/@deepseek-ai%2Fdsh")) {
                        String integrity = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tarball));
                        if (tamper) integrity = "sha512-bm90IHRoZSByaWdodCBieXRlcw==";
                        return new MockResponse().setBody("{\"dist-tags\":{\"latest\":\"" + latest + "\",\"next\":\"0.3.0-rc.1\"},\"versions\":{\"" + latest
                                + "\":{\"dist\":{\"tarball\":\"" + base + "/dsh.tgz\",\"integrity\":\"" + integrity + "\"}}}}");
                    }
                    if (request.getPath().equals("/dsh.tgz")) return new MockResponse().setBody(new Buffer().write(tarball));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        registry.start();
    }

    @After
    public void tearDown() throws Exception {
        registry.shutdown();
    }

    private HarnessUpdater updater() {
        return new HarnessUpdater(updates, "0.2.1-rc.2", bundled).registry(registry.url("/").toString());
    }

    private static HarnessUpdater.Installer fakeNpm() {
        return (version, dir) -> {
            File pkg = HarnessUpdater.dshPackage(dir);
            new File(pkg, "lib").mkdirs();
            new File(pkg, "lib/bin.js").createNewFile();
            return pkg;
        };
    }

    @Test
    public void ordersVersionsLikeSemver() {
        assertTrue(HarnessUpdater.compare("0.2.1-rc.2", "0.2.1-rc.10") < 0);
        assertTrue(HarnessUpdater.compare("0.2.1", "0.2.1-rc.10") > 0);
        assertTrue(HarnessUpdater.compare("0.2.2-alpha.1", "0.2.1") > 0);
        assertTrue(HarnessUpdater.compare("0.2.1-alpha.5", "0.2.1-rc.2") < 0);
        assertTrue(HarnessUpdater.compare("0.10.0", "0.9.9") > 0);
        assertEquals(0, HarnessUpdater.compare("v1.0.0", "1.0.0+build"));
    }

    @Test
    public void aNewerVerifiedHarnessBecomesActive() {
        HarnessUpdater u = updater();
        HarnessUpdater.Result r = u.update(fakeNpm(), (pkg, version) -> assertEquals("0.2.2", version));
        assertEquals(r.message, "updated", r.status);
        assertEquals("0.2.2", u.activeVersion());
        assertEquals("0.2.2", u.currentVersion());
        assertTrue(u.activeDsh().getPath().endsWith("0.2.2/node_modules/@deepseek-ai/dsh"));
        assertEquals("up-to-date", updater().update(fakeNpm(), (pkg, v) -> { }).status);
    }

    @Test
    public void aFailedVerificationKeepsTheBundledHarness() {
        HarnessUpdater u = updater();
        HarnessUpdater.Result r = u.update(fakeNpm(), (pkg, version) -> { throw new IOException("patch check failed: P3 mismatch"); });
        assertEquals("failed", r.status);
        assertTrue(r.message.contains("P3 mismatch"));
        assertNull(u.activeVersion());
        assertEquals(bundled, u.activeDsh());
        assertFalse(new File(updates, "0.2.2").exists());
    }

    @Test
    public void aTamperedDownloadIsNeverInstalled() {
        tamper = true;
        boolean[] installed = {false};
        HarnessUpdater.Result r = updater().update((v, dir) -> { installed[0] = true; return dir; }, (p, v) -> { });
        assertEquals("failed", r.status);
        assertTrue(r.message.contains("checksum"));
        assertFalse(installed[0]);
    }

    @Test
    public void anUpdateThatDoesNotStartFallsBackAndIsNotRetried() throws Exception {
        HarnessUpdater u = updater();
        assertEquals("updated", u.update(fakeNpm(), (p, v) -> { }).status);
        u.markBad();
        assertNull(u.activeVersion());
        assertEquals(bundled, u.activeDsh());
        assertEquals("failed", updater().update(fakeNpm(), (p, v) -> { }).status);
    }

    @Test
    public void theBundledHarnessWinsWhenTheAppShipsANewerOne() {
        assertEquals("updated", updater().update(fakeNpm(), (p, v) -> { }).status);
        HarnessUpdater newerApp = new HarnessUpdater(updates, "0.3.0", bundled);
        assertNull(newerApp.activeVersion());
        assertEquals("0.3.0", newerApp.currentVersion());
    }
}
