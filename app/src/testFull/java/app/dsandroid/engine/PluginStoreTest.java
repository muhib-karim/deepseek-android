package app.dsandroid.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

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

public class PluginStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private MockWebServer server;
    private File dshHome;
    private File profile;
    private PluginStore store;
    private int dependencyRuns;

    private static final String SKILL = "---\nname: fixture-skill\ndescription: A fixture skill for the palette test\n---\n\nDo the thing.\n";

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        dshHome = tmp.newFolder(".dsh");
        profile = new File(dshHome, "profiles/web");
        new File(profile, "node_modules").mkdirs();
        store = new PluginStore(dshHome, profile, tmp.newFolder("work"), dir -> {
            dependencyRuns++;
            new File(dir, "node_modules/left-pad").mkdirs();
        });
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void parsesEverySourceShapeAndExplainsTheRest() throws Exception {
        PluginStore.Spec gh = PluginStore.parse("https://github.com/someone/ponytail/tree/main/skills/ponytail");
        assertEquals("github", gh.type);
        assertEquals("someone/ponytail", gh.location);
        assertEquals("main", gh.ref);
        assertEquals("skills/ponytail", gh.subdir);
        assertEquals("someone/repo", PluginStore.parse("https://github.com/someone/repo.git").location);
        assertEquals("v2", PluginStore.parse("github:someone/repo#v2").ref);
        PluginStore.Spec npm = PluginStore.parse("@scope/dsh-thing@1.2.3");
        assertEquals("npm", npm.type);
        assertEquals("@scope/dsh-thing", npm.location);
        assertEquals("1.2.3", npm.ref);
        assertEquals("url", PluginStore.parse("https://example.com/p.tgz").type);
        assertEquals("local", PluginStore.parse("/data/cache/picked/p.zip").type);
        for (String bad : new String[]{"", "http://github.com/a/b", "https://example.com/page", "not a source!"}) {
            try {
                PluginStore.parse(bad);
                fail("accepted " + bad);
            } catch (PluginStore.PluginException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test
    public void aSkillFolderInstallsIntoTheHarnessSkillRootAndCanBeDisabledAndRemoved() throws Exception {
        File picked = tmp.newFolder("picked", "fixture-skill");
        write(new File(picked, "SKILL.md"), SKILL);
        write(new File(picked, "references/notes.md"), "notes");
        List<PluginStore.Entry> added = store.install(picked.getAbsolutePath());
        assertEquals(1, added.size());
        assertEquals("fixture-skill", added.get(0).name);
        assertEquals(PluginStore.Kind.SKILL, added.get(0).kind);
        assertFalse(PluginStore.needsRestart(added));
        assertEquals(SKILL, read(new File(dshHome, "skills/fixture-skill/SKILL.md")));
        assertTrue(new File(dshHome, "skills/fixture-skill/references/notes.md").isFile());
        assertTrue(store.listJson().toString().contains("\"fixture-skill\""));

        store.setEnabled("fixture-skill", false);
        assertFalse(new File(dshHome, "skills/fixture-skill").exists());
        assertTrue(new File(dshHome, "skills-disabled/fixture-skill/SKILL.md").isFile());
        assertFalse(store.list().get(0).enabled);
        store.setEnabled("fixture-skill", true);
        assertTrue(new File(dshHome, "skills/fixture-skill/SKILL.md").isFile());

        store.remove("fixture-skill");
        assertFalse(new File(dshHome, "skills/fixture-skill").exists());
        assertTrue(store.list().isEmpty());
    }

    @Test
    public void aSkillWithoutFrontMatterDescriptionIsRejectedInline() throws Exception {
        File picked = tmp.newFolder("bad-skill");
        write(new File(picked, "SKILL.md"), "# no front matter\n");
        try {
            store.install(picked.getAbsolutePath());
            fail("skill without description installed");
        } catch (PluginStore.PluginException expected) {
            assertTrue(expected.getMessage().contains("description"));
        }
        assertFalse(new File(dshHome, "skills/bad-skill").exists());
        File nothing = tmp.newFolder("nothing");
        write(new File(nothing, "README.md"), "hi");
        try {
            store.install(nothing.getAbsolutePath());
            fail("folder without plugin or skill installed");
        } catch (PluginStore.PluginException expected) {
            assertTrue(expected.getMessage().startsWith("Nothing to install"));
        }
    }

    @Test
    public void aDshPluginPackageGetsAProfileRowAndItsDependencies() throws Exception {
        write(new File(profile, "cordis.patch.yml"), "# Android: profile rows added by DeepSeek Android\n- insert:\n    - id: dsh-web-mobile\n      name: 'dsh-web-mobile'\n");
        File picked = tmp.newFolder("pkg");
        write(new File(picked, "package.json"), "{\"name\":\"dsh-plugin-fixture\",\"version\":\"1.0.0\",\"dsh\":{},\"dependencies\":{\"left-pad\":\"1.3.0\"}}");
        write(new File(picked, "lib/index.js"), "export const name = 'fixture'");
        List<PluginStore.Entry> added = store.install(picked.getAbsolutePath());
        assertTrue(PluginStore.needsRestart(added));
        assertEquals(1, dependencyRuns);
        File installed = new File(profile, "node_modules/dsh-plugin-fixture");
        assertTrue(new File(installed, "lib/index.js").isFile());
        assertTrue(new File(installed, "node_modules/left-pad").isDirectory());
        String patch = read(new File(profile, "cordis.patch.yml"));
        assertTrue(patch.contains("name: 'dsh-web-mobile'"));
        assertTrue(patch.contains("    - id: user-dsh-plugin-fixture\n      name: 'dsh-plugin-fixture'\n"));

        store.setEnabled("dsh-plugin-fixture", false);
        assertTrue(read(new File(profile, "cordis.patch.yml")).contains("name: 'dsh-plugin-fixture'\n      disabled: true\n"));
        store.remove("dsh-plugin-fixture");
        patch = read(new File(profile, "cordis.patch.yml"));
        assertFalse(patch.contains("dsh-plugin-fixture"));
        assertTrue(patch.contains("name: 'dsh-web-mobile'"));
        assertFalse(installed.exists());
    }

    @Test
    public void builtInPackagesCannotBeReplaced() throws Exception {
        File bundled = tmp.newFolder("bundled", "dsh-web-mobile");
        Files.createSymbolicLink(new File(profile, "node_modules/dsh-web-mobile").toPath(), bundled.toPath());
        File picked = tmp.newFolder("evil");
        write(new File(picked, "package.json"), "{\"name\":\"dsh-web-mobile\",\"dsh\":{}}");
        try {
            store.install(picked.getAbsolutePath());
            fail("replaced a built-in plugin");
        } catch (PluginStore.PluginException expected) {
            assertTrue(expected.getMessage().contains("built-in"));
        }
    }

    @Test
    public void npmPackagesAreVerifiedAgainstTheRegistryChecksum() throws Exception {
        final byte[] tgz = new TestTar().file("package/package.json", "{\"name\":\"dsh-skill-pack\",\"version\":\"2.0.0\"}")
                .file("package/skills/alpha-one/SKILL.md", "---\nname: alpha-one\ndescription: first\n---\n")
                .file("package/skills/beta-two/SKILL.md", "---\nname: beta-two\ndescription: second\n---\n").tgz();
        String integrity = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tgz));
        final String good = "{\"dist-tags\":{\"latest\":\"2.0.0\"},\"versions\":{\"2.0.0\":{\"dist\":{\"tarball\":\"URL/t.tgz\",\"integrity\":\"" + integrity + "\"}}}}";
        final String bad = good.replace(integrity, "sha512-AAAA");
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                String base = server.url("/").toString().replaceAll("/$", "");
                if (path.equals("/dsh-skill-pack")) return new MockResponse().setBody(good.replace("URL", base));
                if (path.equals("/tampered")) return new MockResponse().setBody(bad.replace("URL", base));
                if (path.equals("/t.tgz")) return new MockResponse().setBody(new Buffer().write(tgz));
                if (path.startsWith("/someone/skills/tar.gz/")) return new MockResponse().setBody(new Buffer().write(tgz));
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        String base = server.url("/").toString();
        store.endpoints(base, base);
        List<PluginStore.Entry> added = store.install("dsh-skill-pack");
        assertEquals(2, added.size());
        assertTrue(new File(dshHome, "skills/alpha-one/SKILL.md").isFile());
        assertTrue(new File(dshHome, "skills/beta-two/SKILL.md").isFile());
        try {
            store.install("tampered");
            fail("checksum mismatch installed");
        } catch (PluginStore.PluginException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
        try {
            store.install("no-such-package");
            fail("missing package installed");
        } catch (PluginStore.PluginException expected) {
            assertTrue(expected.getMessage().contains("No npm package"));
        }
        // GitHub archives come from codeload; the repo's single top folder is stripped.
        store.remove("alpha-one");
        store.remove("beta-two");
        assertEquals(2, store.install("https://github.com/someone/skills").size());
        assertEquals("https://github.com/someone/skills", store.list().get(0).source);
        assertEquals(2, store.update("alpha-one").size());
    }
}
