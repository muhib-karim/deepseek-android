package app.dsandroid.engine;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where the engine payload comes from: lib/<abi>/libdshpayload.so in the installed APK (stored, streamed straight from
 * the APK), or nativeLibraryDir when the installer extracted native libraries. The ".so" name is only how ABI splits
 * keep one runtime per APK; the file is the gzip tar built by scripts/build-dsh-payload.sh.
 */
public final class PayloadSource {
    static final String NAME = "libdshpayload.so";

    final String description;
    final long compressedSize;
    final String stamp;
    private final File apk;
    private final String entryName;
    private final File extracted;

    private PayloadSource(String description, long size, String stamp, File apk, String entryName, File extracted) {
        this.description = description;
        this.compressedSize = size;
        this.stamp = stamp;
        this.apk = apk;
        this.entryName = entryName;
        this.extracted = extracted;
    }

    /** The payload for this device's ABI, or null when this build carries none (a build without the CI payload). */
    public static PayloadSource find(Context context) throws IOException {
        ApplicationInfo info = context.getApplicationInfo();
        File native_ = info.nativeLibraryDir == null ? null : new File(info.nativeLibraryDir, NAME);
        if (native_ != null && native_.isFile() && native_.length() > 0) {
            return new PayloadSource(native_.getPath(), native_.length(), "file-" + native_.length() + "-" + native_.lastModified(),
                    null, null, native_);
        }
        List<String> apks = new ArrayList<String>();
        apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) {
                apks.add(split);
            }
        }
        for (String path : apks) {
            ZipFile zip = new ZipFile(path);
            try {
                for (String abi : Build.SUPPORTED_ABIS) {
                    String name = "lib/" + abi + "/" + NAME;
                    ZipEntry e = zip.getEntry(name);
                    if (e != null && e.getSize() > 0) {
                        // CRC + size identify the payload: a new APK with the same payload keeps the extracted tree.
                        return new PayloadSource(path + "!" + name, e.getCompressedSize(),
                                "apk-" + Long.toHexString(e.getCrc()) + "-" + e.getSize(), new File(path), name, null);
                    }
                }
            } finally {
                zip.close();
            }
        }
        return null;
    }

    /** The gzip stream; closing it closes the APK too. */
    InputStream open() throws IOException {
        if (extracted != null) {
            return new FileInputStream(extracted);
        }
        final ZipFile zip = new ZipFile(apk);
        ZipEntry e = zip.getEntry(entryName);
        if (e == null) {
            zip.close();
            throw new IOException("payload vanished from " + apk);
        }
        return new FilterInputStream(zip.getInputStream(e)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zip.close();
                }
            }
        };
    }
}
