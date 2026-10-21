package app.dsandroid.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DshCredentialsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void writesTheVersionedStoreOwnerOnly() throws Exception {
        File home = new File(tmp.getRoot(), ".dsh");
        DshCredentials.write(home, "  sk-test-0123456789abcdef ");
        File f = DshCredentials.file(home);
        assertEquals("version: 1\nrefs:\n  DEEPSEEK_API_KEY: \"sk-test-0123456789abcdef\"\n", read(f));
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(f.toPath()));
        assertTrue(DshCredentials.hasKey(home));
        File[] leftovers = home.listFiles((d, n) -> n.contains(".tmp-"));
        assertNotNull(leftovers);
        assertEquals(0, leftovers.length);
    }

    @Test
    public void replacingTheKeyKeepsOtherRefsAndRecords() throws Exception {
        String existing = "version: 1\nrefs:\n  OTHER_KEY: \"x\"\n  DEEPSEEK_API_KEY: \"old\"\nrecords:\n  a: b\n";
        assertEquals("version: 1\nrefs:\n  DEEPSEEK_API_KEY: \"sk-new-key-123\"\n  OTHER_KEY: \"x\"\nrecords:\n  a: b\n",
                DshCredentials.render(existing, "  DEEPSEEK_API_KEY: \"sk-new-key-123\""));
        assertEquals("version: 1\nrefs:\n  OTHER_KEY: \"x\"\nrecords:\n  a: b\n", DshCredentials.render(existing, null));
        assertEquals("version: 1\nrefs: {}\nrecords:\n  a: b\n",
                DshCredentials.render("version: 1\nrefs:\n  DEEPSEEK_API_KEY: \"old\"\nrecords:\n  a: b\n", null));
    }

    @Test
    public void upgradesTheOldFlatSeedAndClears() throws Exception {
        File home = tmp.newFolder(".dsh");
        Files.write(DshCredentials.file(home).toPath(), "{}\n".getBytes(StandardCharsets.UTF_8));
        DshCredentials.ensure(home);
        assertEquals("version: 1\nrefs: {}\n", read(DshCredentials.file(home)));
        DshCredentials.write(home, "sk-abcdefgh");
        DshCredentials.clear(home);
        assertEquals("version: 1\nrefs: {}\n", read(DshCredentials.file(home)));
        assertFalse(DshCredentials.hasKey(home));
        File fresh = new File(tmp.getRoot(), "fresh");
        DshCredentials.ensure(fresh);
        assertEquals("version: 1\nrefs: {}\n", read(DshCredentials.file(fresh)));
    }

    @Test
    public void rejectsKeysThatCouldBreakTheDocument() {
        assertNotNull(DshCredentials.problem(""));
        assertNotNull(DshCredentials.problem("short"));
        assertNotNull(DshCredentials.problem("sk-abc\"def123"));
        assertNotNull(DshCredentials.problem("sk-abc def123"));
        assertNotNull(DshCredentials.problem("sk-abc\ndef123"));
        assertNull(DshCredentials.problem("sk-0123456789abcdef"));
    }
}
