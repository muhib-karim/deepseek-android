package app.dsandroid.core.python

import org.junit.Assert.assertEquals
import org.junit.Test

class PythonOutputTextTest {
    @Test fun formatsStreamsErrorsTimeoutsAndFiles() {
        assertEquals("4", PythonOutputText.format("""{"stdout":"4\n","stderr":"","timed_out":false,"files":[]}"""))
        assertEquals("partial\n\nstderr:\nwarn\n\nError: ZeroDivisionError: division by zero\n\nFiles in this chat: chart.png, out/data.csv",
            PythonOutputText.format("""{"stdout":"partial","stderr":"warn\n","error":"ZeroDivisionError: division by zero","timed_out":false,"files":["chart.png","out/data.csv"]}"""))
        assertEquals("Stopped at the time limit; Python restarts on the next run.\n\nOutput was shortened.",
            PythonOutputText.format("""{"stdout":"","stderr":"","timed_out":true,"truncated":true,"files":[]}"""))
        assertEquals("Finished with no output.", PythonOutputText.format("""{"stdout":"","stderr":"","timed_out":false,"files":[]}"""))
        assertEquals("plain text", PythonOutputText.format("plain text"))
    }
}
