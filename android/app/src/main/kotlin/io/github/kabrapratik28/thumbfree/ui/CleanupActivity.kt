package io.github.kabrapratik28.thumbfree.ui

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.app.AppGraph
import io.github.kabrapratik28.thumbfree.app.GeminiCleanup
import io.github.kabrapratik28.thumbfree.core.text.CleanupCheck.Verdict
import io.github.kabrapratik28.thumbfree.core.text.CleanupStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Clean up's card (issue #1): a small ThumbFree window by the bubble that never takes focus, while Gemini Nano runs: ML
 * Kit answers only while a ThumbFree activity is resumed. A native field keeps its keyboard and input session meanwhile;
 * a web page's field loses both until the card closes (Chrome, measured on a Pixel). A tap's
 * card says "Cleaning up…" and closes with a safe answer, which Cleanup then writes; a hold's offers the styles, shows
 * the result, and hands it over on Replace. Its own task, out of Recents; it closes when the owner leaves it.
 */
class CleanupActivity : ComponentActivity() {
    companion object {
        const val EXTRA_STYLES = "styles"
        const val EXTRA_ANCHOR = "anchor"
        private const val TAG = "ThumbFree"
    }

    private val cleanup get() = AppGraph.ports.cleanup
    private var styles = false
    private var started = false
    private var job: Job? = null

    // The hold's card: the style picked, its answer as checked (null while it runs), and a failure's message.
    private var picked by mutableStateOf<CleanupStyle?>(null)
    private var verdict by mutableStateOf<Verdict?>(null)
    private var problem by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A card the system brought back has no take to tidy, nor one whose offer went meanwhile.
        if (savedInstanceState != null || !AppGraph.initialized || !cleanup.attach(this)) {
            finish()
            return
        }
        styles = intent.getBooleanExtra(EXTRA_STYLES, false)
        // A tap's card takes no focus, so a native field keeps its keyboard. While ThumbFree is the focused app with no
        // focusable window, a key event (Back) waits, and holds up all input behind it, until one appears; Android calls
        // that not responding after 5 s. So the card becomes focusable after 3 s, and a hold's card, which waits for the
        // owner's choice, is focusable from the start (Back closes it).
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        if (!styles) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            lifecycleScope.launch {
                delay(3_000)
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
        place(intent.getParcelableExtra(EXTRA_ANCHOR, Rect::class.java))
        setContent {
            AppTheme {
                // The window is as big as the card and its shadow: a wrap-content window is otherwise laid out 320 dp
                // wide, and its see-through part would take touches and push the card off the bubble's side. The card
                // is measured unbounded, so its size never depends on the window's.
                val fit = Modifier.wrapContentSize(unbounded = true).padding(6.dp)
                    .onSizeChanged { if (it.width > 0 && it.height > 0) window.setLayout(it.width, it.height) }
                Box(fit) {
                    if (styles) StylesCard() else WorkingCard()
                }
            }
        }
    }

    // The model only answers once the card is resumed: a call from onCreate fails as a background call.
    override fun onResume() {
        super.onResume()
        if (started || isFinishing) return
        started = true
        run(AppGraph.settings.cleanupStyle)
    }

    override fun onDestroy() {
        if (AppGraph.initialized) cleanup.detach(this)
        super.onDestroy()
    }

    /**
     * The card by the bubble: above it, its outer edge in line with the circle's, or below it when the bubble is near the
     * top. Screen pixels, as the bubble's window uses, so no bar or cutout inset shifts it.
     */
    private fun place(anchor: Rect?) {
        val screen = windowManager.currentWindowMetrics.bounds
        val dp = resources.displayMetrics.density
        val circle = anchor ?: Rect(screen.right - (64 * dp).toInt(), screen.centerY(), screen.right - (16 * dp).toInt(), screen.centerY() + (48 * dp).toInt())
        val right = circle.centerX() > screen.centerX()
        val above = circle.top - screen.top > (if (styles) 380 else 120) * dp
        val gap = (8 * dp).toInt()
        window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = (if (above) Gravity.BOTTOM else Gravity.TOP) or (if (right) Gravity.END else Gravity.START)
            x = if (right) screen.right - circle.right else circle.left
            y = if (above) screen.bottom - circle.top + gap else circle.bottom + gap
        }
    }

    /** Runs the model for [style]. A tap's card writes a safe answer at once; a hold's shows it first. */
    private fun run(style: CleanupStyle) {
        val take = cleanup.take() ?: return finish()
        picked = style
        verdict = null
        problem = null
        job?.cancel()
        job = lifecycleScope.launch {
            val at = SystemClock.elapsedRealtime()
            val status = GeminiCleanup.status()
            cleanup.status = status
            val output = try {
                if (status != FeatureStatus.AVAILABLE) return@launch fail(R.string.cleanup_not_ready, "status_$status")
                GeminiCleanup.clean(take, style)
            } catch (e: GenAiException) {
                return@launch fail(message(e.errorCode), "error_${e.errorCode}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch fail(R.string.cleanup_failed, e.javaClass.simpleName)
            }
            val checked = cleanup.check(style, output)
            val code = when (checked) {
                is Verdict.Ok -> "ok"
                Verdict.Same -> "same"
                is Verdict.Rejected -> checked.reason
            }
            Log.i(TAG, "cleanup style=${style.name.lowercase()} verdict=$code ms=${SystemClock.elapsedRealtime() - at}")
            if (styles) {
                verdict = checked
                return@launch
            }
            when (checked) {
                is Verdict.Ok -> {
                    cleanup.deliver(checked.text)
                    finish()
                }
                Verdict.Same -> fail(R.string.cleanup_same, null)
                is Verdict.Rejected -> fail(R.string.cleanup_failed, null)
            }
        }
    }

    /** A clean up that writes nothing: a tap's card says why in a toast and closes; a hold's says it in the card. */
    private fun fail(@StringRes message: Int, code: String?) {
        if (code != null) Log.i(TAG, "cleanup_failed code=$code")
        if (styles) {
            problem = message
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun message(code: Int) = when (code) {
        GenAiException.ErrorCode.BUSY -> R.string.cleanup_busy
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> R.string.cleanup_quota
        else -> R.string.cleanup_failed
    }

    /** A tap's card: "Cleaning up…" with a turning ring, a pill by the bubble. */
    @Composable
    private fun WorkingCard() = Card {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.cleanup_working), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }

    /** A hold's card: the five styles, the picked one's answer (or why there is none), then Cancel and Replace. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun StylesCard() = Card {
        Column(Modifier.width(328.dp).padding(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (style in CleanupStyle.entries) {
                    FilterChip(selected = style == picked, onClick = { run(style) }, label = { Text(stringResource(styleName(style))) })
                }
            }
            // At most about six lines, scrolled, so Cancel and Replace always fit above the bubble.
            Box(
                Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 168.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val v = verdict
                when {
                    problem != null -> Text(stringResource(problem!!), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    v is Verdict.Ok -> Text(v.text, style = MaterialTheme.typography.bodyLarge)
                    v == Verdict.Same -> Text(stringResource(R.string.cleanup_same), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    v is Verdict.Rejected -> Text(stringResource(R.string.cleanup_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    picked != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(styleHint(picked!!)), Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text(stringResource(R.string.cleanup_pick), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.cleanup_cancel)) }
                Button(
                    onClick = {
                        (verdict as? Verdict.Ok)?.let { cleanup.deliver(it.text) }
                        finish()
                    },
                    enabled = verdict is Verdict.Ok && problem == null,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.cleanup_replace)) }
            }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) = Surface(
        shape = RoundedCornerShape(if (styles) 20.dp else 24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        content = content,
    )
}

/** A style's name on the card and in Settings. */
internal fun styleName(style: CleanupStyle) = when (style) {
    CleanupStyle.CLEAN -> R.string.cleanup_style_clean
    CleanupStyle.SHORTER -> R.string.cleanup_style_shorter
    CleanupStyle.FRIENDLY -> R.string.cleanup_style_friendly
    CleanupStyle.PROFESSIONAL -> R.string.cleanup_style_professional
    CleanupStyle.SIMPLE -> R.string.cleanup_style_simple
}

/** What a style does, in one line. */
internal fun styleHint(style: CleanupStyle) = when (style) {
    CleanupStyle.CLEAN -> R.string.cleanup_hint_clean
    CleanupStyle.SHORTER -> R.string.cleanup_hint_shorter
    CleanupStyle.FRIENDLY -> R.string.cleanup_hint_friendly
    CleanupStyle.PROFESSIONAL -> R.string.cleanup_hint_professional
    CleanupStyle.SIMPLE -> R.string.cleanup_hint_simple
}
