package app.dsandroid.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The engine log (filesDir/engine.log, one rotated generation): extraction, launch, harness output, page checks.
 * App-private; overflow menu > Engine status shows and copies its tail. Session tokens are redacted before they get here.
 */
public final class EngineLog {
    private static final long MAX_BYTES = 512 * 1024;

    private EngineLog() {
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), "engine.log");
    }

    public static synchronized void append(Context context, String line) {
        Log.i("DshEngine", line);
        File f = file(context);
        try {
            if (f.length() > MAX_BYTES) {
                File old = new File(f.getPath() + ".1");
                old.delete();
                f.renameTo(old);
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                out.write((stamp + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // logging must never stop the engine
        }
    }

    /** The last maxBytes of the log. */
    public static String tail(Context context, int maxBytes) {
        File f = file(context);
        if (!f.isFile()) {
            return "";
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                long len = raf.length();
                long from = Math.max(0, len - maxBytes);
                byte[] buf = new byte[(int) (len - from)];
                raf.seek(from);
                raf.readFully(buf);
                return new String(buf, StandardCharsets.UTF_8);
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return "";
        }
    }
}
