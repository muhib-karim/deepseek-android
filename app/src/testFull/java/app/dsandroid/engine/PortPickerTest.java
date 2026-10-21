package app.dsandroid.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.Test;

public class PortPickerTest {
    @Test
    public void skipsPortsInUseAndStaysInTheBand() throws Exception {
        ServerSocket busy = new ServerSocket();
        busy.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        try {
            int taken = busy.getLocalPort();
            int picked = PortPicker.pick(taken, 5);
            assertNotEquals(taken, picked);
            assertTrue(picked > taken && picked < taken + 5);
            assertEquals(-1, PortPicker.pick(taken, 1));
        } finally {
            busy.close();
        }
        assertEquals(3220, PortPicker.START);
    }
}
