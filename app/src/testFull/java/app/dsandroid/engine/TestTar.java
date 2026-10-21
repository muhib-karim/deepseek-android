package app.dsandroid.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/** Writes small ustar archives for the tests (regular files, directories, symlinks, PAX long names). */
final class TestTar {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    TestTar file(String name, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (name.length() > 99) {
            pax(name);
            header("pax-placeholder", '0', data.length, "");
        } else {
            header(name, '0', data.length, "");
        }
        out.write(data);
        pad(data.length);
        return this;
    }

    TestTar dir(String name) throws IOException {
        header(name, '5', 0, "");
        return this;
    }

    TestTar symlink(String name, String target) throws IOException {
        header(name, '2', 0, target);
        return this;
    }

    private void pax(String path) throws IOException {
        String record = " path=" + path + "\n";
        int len = record.length() + 2;
        String full = len + record;
        if (full.length() != len) {
            full = full.length() + record;
        }
        byte[] data = full.getBytes(StandardCharsets.UTF_8);
        header("PaxHeader", 'x', data.length, "");
        out.write(data);
        pad(data.length);
    }

    private void header(String name, char type, long size, String link) throws IOException {
        byte[] h = new byte[512];
        put(h, 0, name, 100);
        put(h, 100, "0000755", 8);
        put(h, 108, "0000000", 8);
        put(h, 116, "0000000", 8);
        put(h, 124, String.format("%011o", size), 12);
        put(h, 136, "00000000000", 12);
        put(h, 148, "        ", 8);
        h[156] = (byte) type;
        put(h, 157, link, 100);
        put(h, 257, "ustar", 6);
        put(h, 263, "00", 2);
        out.write(h);
    }

    private static void put(byte[] h, int off, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len));
    }

    private void pad(long size) {
        long rem = size % 512;
        for (long i = 0; rem != 0 && i < 512 - rem; i++) {
            out.write(0);
        }
    }

    byte[] tar() {
        byte[] end = new byte[1024];
        out.write(end, 0, end.length);
        return out.toByteArray();
    }

    byte[] tgz() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(gz);
        g.write(tar());
        g.close();
        return gz.toByteArray();
    }
}
