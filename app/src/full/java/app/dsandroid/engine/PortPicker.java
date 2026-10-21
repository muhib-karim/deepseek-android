package app.dsandroid.engine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * The harness port: the first free loopback port from 3220 upward (dsh-android's freePort, MIT; see RuntimeEnv).
 * Loopback is shared by every app on Android, so the port is not a secret: the harness's own session token is.
 */
public final class PortPicker {
    public static final int START = 3220;
    public static final int SPAN = 50;

    private PortPicker() {
    }

    /** First port in [start, start + span) that can be bound on 127.0.0.1, or -1 when none can. */
    public static int pick(int start, int span) {
        for (int p = start; p < start + span && p <= 65535; p++) {
            if (free(p)) {
                return p;
            }
        }
        return -1;
    }

    static boolean free(int port) {
        ServerSocket s = null;
        try {
            s = new ServerSocket();
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return true;
        } catch (IOException inUse) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
