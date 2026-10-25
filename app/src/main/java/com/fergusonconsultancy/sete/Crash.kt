package com.fergusonconsultancy.sete

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Catches any crash, keeps its report, and shows it on the next open so it can be sent back. */
class SeteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val report = buildString {
                    append("Sete ").append(BuildConfig.VERSION_NAME).append(" · ").append(Build.MANUFACTURER).append(' ')
                        .append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Thread: ").append(t.name).append("\n\n")
                    append(android.util.Log.getStackTraceString(e))
                }
                Crash.file(this).writeText(report)
            }
            previous?.uncaughtException(t, e)
        }
    }
}

object Crash {
    fun file(c: android.content.Context) = File(c.filesDir, "crash.txt")

    /** Shows the last crash, if there is one, using only plain views. Returns true when it did. */
    fun show(a: Activity): Boolean {
        val f = file(a)
        if (!f.exists()) return false
        val report = runCatching { f.readText() }.getOrDefault("")
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(56), px(22), px(40))
            setBackgroundColor(0xFF0E1311.toInt())
        }
        col.addView(TextView(a).apply { text = "Sete closed unexpectedly"; textSize = 24f; setTextColor(0xFFF6F1E6.toInt()) })
        col.addView(TextView(a).apply {
            text = "Send this report back so it can be fixed. Then tap Try again."
            textSize = 15f; setTextColor(0xFF96A098.toInt()); setPadding(0, px(8), 0, px(16))
        })
        fun button(label: String, onTap: () -> Unit) = Button(a).apply { text = label; isAllCaps = false; setOnClickListener { onTap() } }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        row.addView(button("Share report") {
            runCatching {
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Sete crash report").putExtra(Intent.EXTRA_TEXT, report), "Share report"))
            }
        })
        row.addView(button("Copy") {
            runCatching { a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sete crash", report)) }
        })
        row.addView(button("Try again") { f.delete(); a.recreate() })
        col.addView(row)
        col.addView(TextView(a).apply {
            text = report; textSize = 12f; setTextColor(0xFFD9BB86.toInt()); setTextIsSelectable(true); setPadding(0, px(18), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        })
        a.setContentView(ScrollView(a).apply { setBackgroundColor(Color.parseColor("#0E1311")); addView(col) })
        return true
    }
}
