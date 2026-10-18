package app.dsandroid.core.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun `English and Bangla sentences keep original highlight offsets`() {
        val text = "Hello world!  কেমন আছেন। ভালো আছি।\nGoodbye."
        assertEquals(listOf("Hello world!", "কেমন আছেন।", "ভালো আছি।", "Goodbye."), SpeechText.sentences(text).map { text.substring(it.start, it.end) })
    }
    @Test fun `closing quotes stay with the spoken sentence`() {
        val text = "She said \"Hello.\" Next sentence."
        assertEquals(listOf("She said \"Hello.\"", "Next sentence."), SpeechText.sentences(text).map { text.substring(it.start, it.end) })
    }
    @Test fun `decimal numbers do not break sentences`() {
        val text = "It costs 3.14 today. Next?"
        assertEquals(listOf("It costs 3.14 today.", "Next?"), SpeechText.sentences(text).map { text.substring(it.start, it.end) })
    }
    @Test fun `long speech is bounded without dropping or splitting surrogate pairs`() {
        val text = "a".repeat(999) + "😀" + "b".repeat(2500)
        val ranges = SpeechText.sentences(text)
        assertEquals(text, ranges.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(ranges.all { it.end - it.start <= 1000 })
        assertTrue(ranges.none { Character.isLowSurrogate(text[it.start]) })
    }
    @Test fun `long quote suffixes cannot exceed the speech engine input bound`() {
        val text = "a".repeat(998) + "." + "\"".repeat(2000)
        assertTrue(SpeechText.sentences(text).all { it.end - it.start <= 1000 })
    }
    @Test fun `reading removes formatting and link targets while retaining visible answer text`() {
        val markdown = "# **Hello**\n\nRead [this label](https://example.org) and `code`.\n\n- One\n- Two"
        assertEquals("Hello\n\nRead this label and code.\n\nOne\nTwo", SpeechText.readableMarkdown(markdown))
    }
    @Test fun `code and table values remain in spoken text without fence syntax`() {
        val markdown = "```text\nvalue = 2 * 3\n```\n\n| Name | Count |\n| --- | --- |\n| Items | 6 |"
        assertEquals("value = 2 * 3\n\nName; Count\nItems; 6", SpeechText.readableMarkdown(markdown))
    }
    @Test fun `empty input has no utterances`() { assertTrue(SpeechText.sentences(" \n\t").isEmpty()) }
    @Test fun `Bangla and English select corresponding installed voices`() {
        assertEquals("bn", SpeechText.language("বাংলা উত্তর"))
        assertEquals("en", SpeechText.language("English answer"))
    }
    @Test fun `nonfinite and out of range speed never reach speech engine`() {
        assertEquals(1f, SpeechText.speed(Float.NaN), 0f)
        assertEquals(0.5f, SpeechText.speed(-2f), 0f)
        assertEquals(2f, SpeechText.speed(20f), 0f)
    }
}
