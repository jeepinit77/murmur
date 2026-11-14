package app.murmur.system

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.murmur.R
import app.murmur.container
import app.murmur.ui.MainActivity
import java.util.Locale

/** Answers Settings' "is voice data installed?" query with the installed languages. */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voices = container.voices
        fun code(tag: String): String {
            val l = Locale.forLanguageTag(tag)
            val lang = MurmurTtsService.iso3Lang(l.language).orEmpty()
            val country = MurmurTtsService.iso3Country(l.country)
            return if (country.isNullOrEmpty()) lang else "$lang-$country"
        }
        val available = voices.installedPacks().flatMap { it.languages }.map(::code).distinct()
        val unavailable = voices.catalog.voices.flatMap { it.languages }.map(::code).distinct() - available.toSet()
        val result = Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(available))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList(unavailable))
        setResult(
            if (available.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            result,
        )
        finish()
    }
}

/** Provides the sentence spoken by Settings' "Play" / "Listen to an example" button. */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra("sampleText", getString(R.string.sample_text)))
        finish()
    }
}

/** "Install voice data" from Settings opens the voice picker. */
class InstallVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_VOICES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
