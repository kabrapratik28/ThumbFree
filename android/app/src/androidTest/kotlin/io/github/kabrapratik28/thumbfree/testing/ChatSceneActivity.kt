package io.github.kabrapratik28.thumbfree.testing

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A neutral chat-like screen with made-up messages and a message field (content description "message"), for the store
 * screenshots of the bubble in another app. No brand, no real content. Start it with
 * `am start -n io.github.kabrapratik28.thumbfree.test/io.github.kabrapratik28.thumbfree.testing.ChatSceneActivity`.
 *
 * Like [InsertTargetsActivity] it runs in the test package's process, which has no Kotlin runtime: no kotlin.* calls,
 * so no lambdas, no !!, no companion object, and only private functions take non-null parameters.
 */
class ChatSceneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.setBackgroundColor(Color.rgb(0xF4, 0xF5, 0xF7))

        val header = TextView(this)
        header.text = "Maya"
        header.textSize = 20f
        header.setTextColor(Color.rgb(0x1C, 0x1C, 0x1E))
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(20), 0, dp(20), 0)
        header.setBackgroundColor(Color.WHITE)
        root.addView(header, MATCH_PARENT, dp(64))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(12), dp(12), dp(12), dp(12))
        addMessage(list, "Hey! Are we still on for dinner tonight?", false)
        addMessage(list, "Yes, I booked a table for seven.", true)
        addMessage(list, "Perfect. Could you pick up the cake on your way?", false)
        val scroll = ScrollView(this)
        scroll.addView(list, MATCH_PARENT, WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val field = EditText(this)
        field.contentDescription = "message"
        field.hint = "Message"
        field.textSize = 17f
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.background = pill(Color.WHITE)
        field.setPadding(dp(20), dp(14), dp(20), dp(14))
        val fieldParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        fieldParams.setMargins(dp(12), dp(8), dp(12), dp(12))
        root.addView(field, fieldParams)
        setContentView(root)
        // Dark status and navigation bar icons on the light scene, as a light app draws them.
        val bars = window.insetsController
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        if (bars != null) bars.setSystemBarsAppearance(light, light)
    }

    private fun addMessage(list: LinearLayout, text: String, mine: Boolean) {
        val bubble = TextView(this)
        bubble.text = text
        bubble.textSize = 17f
        bubble.setTextColor(if (mine) Color.WHITE else Color.rgb(0x1C, 0x1C, 0x1E))
        bubble.background = pill(if (mine) Color.rgb(0x2F, 0x6F, 0xEB) else Color.rgb(0xE6, 0xE7, 0xEB))
        bubble.setPadding(dp(16), dp(10), dp(16), dp(10))
        bubble.maxWidth = dp(280)
        val params = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        params.gravity = if (mine) Gravity.END else Gravity.START
        params.setMargins(0, dp(6), 0, dp(6))
        list.addView(bubble, params)
    }

    private fun pill(color: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(22).toFloat()
        return shape
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
