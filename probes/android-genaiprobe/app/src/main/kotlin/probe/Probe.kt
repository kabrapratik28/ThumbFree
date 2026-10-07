package probe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Fetches Gemini Nano through AICore when it is DOWNLOADABLE, logging progress about every 5%. */
suspend fun ensureModel(context: Context, onLine: (String) -> Unit) {
    val model = Generation.getClient()
    val status = model.checkStatus()
    fun log(line: String) { Log.i("GENAIPROBE", line); File(context.filesDir, "probe.log").appendText(line + "\n"); onLine(line) }
    log("download check status=$status")
    if (status != FeatureStatus.DOWNLOADABLE && status != FeatureStatus.DOWNLOADING) return
    var last = -1L
    model.download().collect { s ->
        when (s) {
            is DownloadStatus.DownloadStarted -> log("download started, bytes=${s.bytesToDownload}")
            is DownloadStatus.DownloadProgress -> {
                val mb = s.totalBytesDownloaded / 1_000_000
                if (mb / 50 != last) { last = mb / 50; log("download progress ${mb} MB") }
            }
            DownloadStatus.DownloadCompleted -> log("download completed")
            is DownloadStatus.DownloadFailed -> log("download failed: ${s.e.javaClass.simpleName}: ${s.e.message}")
        }
    }
}

/** One Gemini Nano call through ML Kit's Prompt API from a named context; every result goes to logcat and probe.log. */
object Probe {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private const val PROMPT = "Clean up this dictated text. Remove repeats and fillers, keep only the final version of a " +
        "self-correction, write times as digits, keep every name. Return only the text.\n\nyes yes I booked a table for like seven no seven thirty"

    private const val RAW = "yes yes I booked a table for like seven no seven thirty at the Italian place on Main Street"

    /** Each API's status, then one call on the first that is AVAILABLE (Proofreading VOICE first, our Clean case). */
    suspend fun run(context: Context, label: String): String {
        val t0 = System.currentTimeMillis()
        val line = try {
            val proof = Proofreading.getClient(
                ProofreaderOptions.builder(context).setInputType(ProofreaderOptions.InputType.VOICE)
                    .setLanguage(ProofreaderOptions.Language.ENGLISH).build(),
            )
            val rewrite = Rewriting.getClient(
                RewriterOptions.builder(context).setOutputType(RewriterOptions.OutputType.FRIENDLY)
                    .setLanguage(RewriterOptions.Language.ENGLISH).build(),
            )
            val prompt = Generation.getClient()
            val ps = proof.checkFeatureStatus().await()
            val rs = rewrite.checkFeatureStatus().await()
            val gs = prompt.checkStatus()
            val statuses = "status proofread=$ps rewrite=$rs prompt=$gs"
            val parts = mutableListOf(statuses)
            if (gs == FeatureStatus.AVAILABLE) {
                val t = System.currentTimeMillis()
                parts += "prompt ${System.currentTimeMillis() - t} ms: " + prompt.generateContent(PROMPT).candidates.firstOrNull()?.text?.trim() +
                    " (${System.currentTimeMillis() - t} ms)"
            }
            if (ps == FeatureStatus.AVAILABLE) {
                val t = System.currentTimeMillis()
                val r = proof.runInference(ProofreadingRequest.builder(RAW).build()).await().results.firstOrNull()?.text
                parts += "proofread (${System.currentTimeMillis() - t} ms): $r"
            }
            if (rs == FeatureStatus.AVAILABLE) {
                val t = System.currentTimeMillis()
                val r = rewrite.runInference(RewritingRequest.builder(RAW).build()).await().results.firstOrNull()?.text
                parts += "rewrite friendly (${System.currentTimeMillis() - t} ms): $r"
            }
            "$label " + parts.joinToString(" | ")
        } catch (e: Exception) {
            val code = (e as? com.google.mlkit.genai.common.GenAiException)?.errorCode
            "$label ERROR code=$code ${e.javaClass.simpleName}: ${e.message}"
        }
        Log.i("GENAIPROBE", line)
        File(context.filesDir, "probe.log").appendText(line + "\n")
        return line
    }
}

/** In front: status, then one call once the screen is resumed (1.5 s after onResume); with "delay", it waits that many
 * seconds first (press Home meanwhile, for the background case). */
class MainActivity : Activity() {
    private lateinit var view: TextView
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { textSize = 16f; setPadding(48, 160, 48, 48); text = "GenAI probe: running…" }
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        val wait = intent.getIntExtra("delay", 0)
        Probe.scope.launch {
            delay(1500)
            if (intent.getBooleanExtra("download", false)) ensureModel(applicationContext) { view.text = it }
            if (wait > 0) delay(wait * 1000L)
            view.text = Probe.run(applicationContext, if (wait > 0) "main-after-${wait}s" else "main-foreground")
        }
    }
}

/** A small card over another app that takes no focus, so that app keeps its keyboard: is this "in front" for AICore? */
class FloatActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        val view = TextView(this).apply { textSize = 15f; setBackgroundColor(Color.WHITE); setTextColor(Color.BLACK); setPadding(40, 30, 40, 30); text = "Cleaning up…" }
        setContentView(view)
        Probe.scope.launch { delay(1500); view.text = Probe.run(applicationContext, "float-nonfocusable"); delay(4000); finish() }
    }
}

/** A focusable sheet over another app (the keyboard drops): the known-good case. */
class SheetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 15f; setBackgroundColor(Color.WHITE); setTextColor(Color.BLACK); setPadding(40, 30, 40, 30); text = "Cleaning up…"; gravity = Gravity.CENTER }
        setContentView(view)
        Probe.scope.launch { delay(1500); view.text = Probe.run(applicationContext, "sheet-focusable"); delay(4000); finish() }
    }
}

/** A call from the background (a broadcast while another app is in front): expected to be blocked. */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Probe.scope.launch { Probe.run(context.applicationContext, "receiver-background"); pending.finish() }
    }
}


/** Runs each case (base64 extras, one per line) through the Prompt API with the given instruction, in front. */
class EvalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 14f; setPadding(48, 160, 48, 48); text = "eval running…" }
        setContentView(view)
        fun decode(key: String) = String(android.util.Base64.decode(intent.getStringExtra(key) ?: "", android.util.Base64.DEFAULT))
        val instruction = decode("instr")
        val cases = decode("cases").lines().filter { it.isNotBlank() }
        val out = File(filesDir, "eval.log").apply { writeText("") }
        Probe.scope.launch {
            delay(1500)
            val model = Generation.getClient()
            for ((i, case) in cases.withIndex()) {
                val t = System.currentTimeMillis()
                val line = try {
                    val prompt = if ("{TAKE}" in instruction) instruction.replace("{TAKE}", case) else "$instruction\n\nText: $case\nCleaned text:"
                    val text = model.generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0f; topK = 1 }).candidates.firstOrNull()?.text?.trim()
                    "$i\t${System.currentTimeMillis() - t}\t$text"
                } catch (e: Exception) {
                    "$i\t-1\tERROR ${(e as? com.google.mlkit.genai.common.GenAiException)?.errorCode} ${e.message}"
                }
                out.appendText(line.replace("\n", " / ") + "\n")
                view.text = "case ${i + 1}/${cases.size}"
            }
            out.appendText("DONE\n")
            view.text = "eval done"
        }
    }
}
