package dev.remotesession.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.remotesession.RemoteSession

/** A plain Views screen, without AndroidX: switches, radio buttons, a text field, a popup menu and an approval. */
class TaskDetailActivity : Activity() {

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val task = tasks[intent.getIntExtra("index", 0)]
        title = task.label
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val summary = TextView(this).apply {
            text = "Estimate ${task.estimate}"
            textSize = 22f
        }
        val done = Switch(this).apply {
            text = "Done"
            textSize = 17f
            setOnCheckedChangeListener { _, checked -> summary.text = "Estimate ${task.estimate}" + if (checked) " · Done" else "" }
        }
        val priority = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val buttons = listOf("Low", "Medium", "High").map { name ->
                RadioButton(this@TaskDetailActivity).apply { id = View.generateViewId(); text = name; textSize = 16f }
            }
            buttons.forEach { addView(it, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            check(buttons[1].id)
        }
        val comment = EditText(this).apply {
            hint = "Add a comment"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val options = Button(this).apply { text = "Options…"; isAllCaps = false }
        options.setOnClickListener {
            PopupMenu(this, options).apply {
                listOf("Remind me", "Flag", "Duplicate", "Move to next week").forEach { menu.add(it) }
                setOnMenuItemClickListener { item -> options.text = "Options: ${item.title}"; true }
                show()
            }
        }
        val toast = TextView(this).apply {
            text = "Task saved"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.rgb(52, 199, 89)) }
            visibility = View.GONE
        }
        val save = Button(this).apply {
            text = "Save"
            isAllCaps = false
            setOnClickListener {
                toast.visibility = View.VISIBLE
                toast.removeCallbacks(null)
                toast.postDelayed({ toast.visibility = View.GONE }, 2500)
            }
        }
        val recoveryKey = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Recovery key"
            isAllCaps = false
            setOnClickListener {
                // When the agent taps this, the user is asked first.
                RemoteSession.confirmAgentAction("Open the recovery key") { allowed ->
                    if (allowed) startActivity(Intent(this@TaskDetailActivity, RecoveryKeyActivity::class.java))
                }
            }
        }

        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            for (view in listOf(summary, done, priority, comment, options, save, recoveryKey)) {
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() })
            }
        }
        val root = FrameLayout(this).apply {
            addView(ScrollView(this@TaskDetailActivity).apply { addView(stack) })
            addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (70 * dp).toInt() })
        }
        setContentView(root)
        fitBelowBars(root)
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }
}

/** Keeps content clear of the status bar, navigation bar and keyboard now that every app draws edge to edge. */
internal fun fitBelowBars(view: View) {
    view.setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        val bars = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()) else insets.systemWindowInsets
        v.setPadding(0, bars.top, 0, bars.bottom)
        insets
    }
}
