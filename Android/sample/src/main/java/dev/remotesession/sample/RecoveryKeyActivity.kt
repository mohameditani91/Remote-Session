package dev.remotesession.sample

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Masked as a whole screen through `MaskRules.activities` in [Support]. */
class RecoveryKeyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Recovery key"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val key = TextView(this).apply {
            text = "K7QD-29XF-M4LP\nRW8N-3HCT-V6ZB"
            textSize = 24f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        setContentView(key)
        fitBelowBars(key)
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }
}
