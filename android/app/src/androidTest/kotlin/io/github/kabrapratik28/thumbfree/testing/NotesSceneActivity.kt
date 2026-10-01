package io.github.kabrapratik28.thumbfree.testing

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A neutral notes-like screen: a made-up note whose body (content description "note") holds a long paragraph, as a
 * take dictated into it would, for the store screenshot of long notes. The body has focus and the keyboard from the
 * start, with the cursor at the end, since a tap would also show the cursor's drag handle. No brand, no real content.
 * Start it with
 * `am start -n io.github.kabrapratik28.thumbfree.test/io.github.kabrapratik28.thumbfree.testing.NotesSceneActivity`.
 *
 * Like [ChatSceneActivity] it runs in the test package's process, which has no Kotlin runtime: no kotlin.* calls, so
 * no lambdas, no !!, no companion object, and only private functions take non-null parameters.
 */
class NotesSceneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.setBackgroundColor(Color.rgb(0xF4, 0xF5, 0xF7))

        val header = TextView(this)
        header.text = "Notes"
        header.textSize = 20f
        header.setTextColor(Color.rgb(0x1C, 0x1C, 0x1E))
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(20), 0, dp(20), 0)
        header.setBackgroundColor(Color.WHITE)
        root.addView(header, MATCH_PARENT, dp(64))

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.background = card()
        page.setPadding(dp(20), dp(18), dp(20), dp(12))
        val title = TextView(this)
        title.text = "Lake trip"
        title.textSize = 24f
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.setTextColor(Color.rgb(0x1C, 0x1C, 0x1E))
        page.addView(title, MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val body = EditText(this)
        body.contentDescription = "note"
        body.textSize = 17f
        body.setLineSpacing(0f, 1.15f)
        body.setTextColor(Color.rgb(0x1C, 0x1C, 0x1E))
        body.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        body.gravity = Gravity.TOP or Gravity.START
        body.background = null
        body.setPadding(0, dp(10), 0, 0)
        body.setText(NOTE)
        page.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val pageParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        pageParams.setMargins(dp(12), dp(12), dp(12), dp(12))
        root.addView(page, pageParams)
        setContentView(root)
        body.requestFocus()
        body.setSelection(NOTE.length)
        // Dark status and navigation bar icons on the light scene, as a light app draws them.
        val bars = window.insetsController
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        if (bars != null) bars.setSystemBarsAppearance(light, light)
    }

    private fun card(): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(Color.WHITE)
        shape.cornerRadius = dp(16).toFloat()
        return shape
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** The made-up note, one long paragraph as it would come from a single take. */
private const val NOTE = "Pack sunscreen, two towels, the blue cooler and snacks for the kids. Ask Sam to bring the " +
    "grill and charcoal, and check that the tent still has all its poles. Fill up the tank and check the tires on " +
    "Friday night, so we can leave at eight on Saturday and miss the traffic. On the way home on Sunday, stop at the " +
    "farm stand for peaches and sweet corn, and drop the cabin key at the front desk before noon."
