package app.murmur.voices

import app.murmur.audio.Pcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun bundledCatalogParses() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val catalog = VoiceCatalog.parse(File(root, "app/src/main/assets/voices.json").readText())
        assertTrue(catalog.voices.size >= 3)
        assertEquals(catalog.voices.size, catalog.voices.map { it.id }.toSet().size)
        catalog.voices.forEach { v ->
            assertTrue(v.url.startsWith("https://"))
            assertTrue(v.url.endsWith(".tar.bz2"))
            assertTrue(v.defaultSpeaker == 0 || v.speakers.isEmpty() || v.defaultSpeaker < v.speakers.size)
        }
        assertEquals(EngineType.KOKORO, catalog.voices.first().engine)
    }

    @Test fun discoversVoiceFiles() {
        val dir = tmp.newFolder("v")
        assertNull(VoiceFiles.discover(dir))
        File(dir, "model.onnx").writeText("x")
        File(dir, "model.int8.onnx").writeText("x")
        File(dir, "tokens.txt").writeText("x")
        File(dir, "espeak-ng-data").mkdir()
        File(dir, "lexicon-us-en.txt").writeText("x")
        val f = VoiceFiles.discover(dir)!!
        assertEquals("model.int8.onnx", f.model.name)
        assertNotNull(f.espeakData)
        assertNull(f.voicesBin)
        assertEquals(1, f.lexicons.size)
    }

    @Test fun pcmConversion() {
        val bytes = Pcm.floatToPcm16(floatArrayOf(0f, 1f, -1f, 2f))
        assertArrayEquals(byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0x01, 0x80.toByte(), 0xff.toByte(), 0x7f), bytes)
    }

    @Test fun trimsSilence() {
        val s = FloatArray(1000) + floatArrayOf(0.5f, 0.5f) + FloatArray(1000)
        val t = Pcm.trimSilence(s, sampleRate = 1000, keepMs = 10)
        assertEquals(22, t.size)
    }
}
