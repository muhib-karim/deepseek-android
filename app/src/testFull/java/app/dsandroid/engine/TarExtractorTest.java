package app.dsandroid.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TarExtractorTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void unpacksFilesDirectoriesRelativeSymlinksAndPaxLongNames() throws Exception {
        String longName = "dsh/node_modules/@deepseek-ai/" + "a-very-long-package-name-that-needs-pax/".repeat(3) + "index.js";
        byte[] tar = new TestTar().dir("usr/bin/").file("usr/bin/node", "#!fake").file("usr/lib/libz.so.1.3.2", "z")
                .symlink("usr/lib/libz.so.1", "libz.so.1.3.2").file(longName, "export {}").tar();
        File dest = tmp.newFolder("runtime");
        int[] seen = {0};
        TarExtractor.extract(new ByteArrayInputStream(tar), dest, name -> seen[0]++);
        assertEquals("#!fake", read(new File(dest, "usr/bin/node")));
        assertTrue(new File(dest, "usr/bin/node").canExecute());
        File link = new File(dest, "usr/lib/libz.so.1");
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals("libz.so.1.3.2", Files.readSymbolicLink(link.toPath()).toString());
        assertEquals("z", read(link));
        assertEquals("export {}", read(new File(dest, longName)));
        assertEquals(5, seen[0]);
    }

    @Test
    public void refusesSymlinksThatPointOutOfTheTree() throws Exception {
        File dest = tmp.newFolder("runtime");
        byte[] tar = new TestTar().symlink("usr/bin/evil", "../../../outside").tar();
        try {
            TarExtractor.extract(new ByteArrayInputStream(tar), dest, null);
            fail("escaping symlink accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
        byte[] absolute = new TestTar().symlink("usr/bin/sh", "/system/bin/sh").tar();
        try {
            TarExtractor.extract(new ByteArrayInputStream(absolute), dest, null);
            fail("absolute symlink out of the tree accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
    }

    @Test
    public void skipsTraversalNamesAndReplacesAnEarlierSymlinkInsteadOfWritingThroughIt() throws Exception {
        File dest = tmp.newFolder("runtime");
        File outside = tmp.newFile("victim");
        byte[] tar = new TestTar().file("../victim", "overwritten").file("a/target", "t").symlink("a/link", "target")
                .file("a/link", "replaced").tar();
        TarExtractor.extract(new ByteArrayInputStream(tar), dest, null);
        assertEquals("", read(outside));
        assertFalse(Files.isSymbolicLink(new File(dest, "a/link").toPath()));
        assertEquals("replaced", read(new File(dest, "a/link")));
        assertEquals("t", read(new File(dest, "a/target")));
    }

    @Test
    public void countingStreamReportsConsumedBytes() throws Exception {
        TarExtractor.Counting c = new TarExtractor.Counting(new ByteArrayInputStream(new byte[1000]));
        c.read(new byte[300]);
        c.read();
        assertEquals(301, c.count);
        assertEquals("/a/c", TarExtractor.normalizePath("/a/b/../c/./"));
    }
}
