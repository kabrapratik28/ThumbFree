package io.github.kabrapratik28.thumbfree.app

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import io.github.kabrapratik28.thumbfree.core.text.CleanupPrompt
import io.github.kabrapratik28.thumbfree.core.text.CleanupStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Gemini Nano through ML Kit's Prompt API, which Android's AICore runs. It answers only while a ThumbFree activity is
 * resumed: from the accessibility service a call fails with BACKGROUND_USE_BLOCKED, so Clean up calls it from
 * CleanupActivity.
 */
object GeminiCleanup {
    private val model by lazy { Generation.getClient() }

    /** FeatureStatus: UNAVAILABLE, DOWNLOADABLE, DOWNLOADING or AVAILABLE; UNAVAILABLE when the check itself fails. */
    suspend fun status(): Int = try {
        model.checkStatus()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("ThumbFree", "cleanup_status_error ${e.javaClass.name}", e) // no text in this call: the trace is safe to log
        FeatureStatus.UNAVAILABLE
    }

    /** AICore's download of the model, which other apps share: started, progress, completed or failed. */
    fun download(): Flow<DownloadStatus> = model.download()

    /** The model's answer for [take] in [style], greedy, so the same words give the same answer; one retry when busy. */
    suspend fun clean(take: String, style: CleanupStyle): String? {
        val request = generateContentRequest(TextPart(CleanupPrompt.build(take, style))) {
            temperature = 0f
            topK = 1
        }
        val response = try {
            model.generateContent(request)
        } catch (e: GenAiException) {
            if (e.errorCode != GenAiException.ErrorCode.BUSY) throw e
            delay(500)
            model.generateContent(request)
        }
        val candidate = response.candidates.firstOrNull() ?: return null
        // An answer cut off at the token limit could read as a tidy that dropped the take's end.
        if (candidate.finishReason != null && candidate.finishReason != Candidate.FinishReason.STOP) return null
        return candidate.text
    }
}
