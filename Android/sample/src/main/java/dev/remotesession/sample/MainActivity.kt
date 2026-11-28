package dev.remotesession.sample

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SampleTheme { App() } }
        Support.startIfRequested(this)
    }
}

private val Indigo = Color(0xFF5856D6)

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8E8CF0)) else lightColorScheme(primary = Indigo)
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Tasks" to R.drawable.tab_tasks, "Profile" to R.drawable.tab_profile, "Support" to R.drawable.tab_support)
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (title, icon) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(painterResource(icon), null) }, label = { Text(title) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> TasksScreen()
                1 -> ProfileScreen()
                else -> SupportScreen()
            }
        }
    }
}

/** A RecyclerView of plain Views inside Compose. Each row opens a Views activity. */
@Composable
private fun TasksScreen() {
    Column {
        Text("Tasks", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
        AndroidView(factory = { context ->
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
                adapter = TasksAdapter()
            }
        }, modifier = Modifier.fillMaxSize().clipToBounds())
    }
}

data class Todo(val number: Int, val hours: Int) {
    val label get() = "Task #$number"
    val estimate get() = "${hours}h"
}

val tasks = (0 until 40).map { Todo(1001 + it, (it * 7) % 12 + 1) }

private class TasksAdapter : RecyclerView.Adapter<TasksAdapter.Row>() {

    class Row(val view: LinearLayout, val label: TextView, val estimate: TextView) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = tasks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val label = TextView(context).apply { textSize = 17f }
        val estimate = TextView(context).apply { textSize = 17f; alpha = 0.6f; gravity = Gravity.END }
        val chevron = TextView(context).apply { text = "›"; textSize = 22f; alpha = 0.4f; setPadding((12 * density).toInt(), 0, 0, 0) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * density).toInt()
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val ripple = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ripple.getDrawable(0)
            ripple.recycle()
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(estimate)
            addView(chevron)
        }
        return Row(row, label, estimate)
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        val task = tasks[position]
        holder.label.text = task.label
        holder.estimate.text = task.estimate
        holder.view.setOnClickListener {
            it.context.startActivity(Intent(it.context, TaskDetailActivity::class.java).putExtra("index", position))
        }
    }
}
