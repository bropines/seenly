package io.github.bropines.lens.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import io.github.bropines.lens.MainActivity
import java.io.File

/**
 * Makes Lens selectable as the phone's digital assistant (Settings → Apps →
 * Default apps → Digital assistant). Long-pressing Home, or the assistant
 * gesture, then hands Lens a screenshot of whatever is on screen, which opens
 * straight in the viewer and gets translated: no MediaProjection prompt, no
 * floating button in the way.
 */
class LensAssistService : VoiceInteractionService()

class LensAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = LensAssistSession(this)
}

/** Takes the screenshot the system sends and opens it in the viewer. Shows no UI of its own. */
class LensAssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot == null) {
            // The user turned off "Use screenshot" for the assistant: open the app plainly.
            startAssistantActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val dir = File(context.cacheDir, "assist").apply { mkdirs() }
        // Keep only the latest few captures.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val file = File(dir, "screen-${System.currentTimeMillis()}.png")
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        startAssistantActivity(
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_ASSIST_CAPTURE)
                .putExtra(MainActivity.EXTRA_CAPTURE_PATH, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}

/**
 * The system will not list an assistant without a recognition service. Lens
 * does no speech recognition; this exists only to satisfy that check.
 */
class LensRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
