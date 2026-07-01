package com.overlaynote.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SessionActivity : AppCompatActivity() {

    private var sessionId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)

        sessionId = intent.getStringExtra("session_id") ?: run {
            finish(); return
        }

        val session = SessionManager.getSession(this, sessionId) ?: run {
            finish(); return
        }

        findViewById<TextView>(R.id.tvSubject).text = session.subject
        findViewById<TextView>(R.id.tvTopic).text   = session.topic
        findViewById<TextView>(R.id.tvDate).text    = "${session.date} · ${session.created}"
        findViewById<TextView>(R.id.tvPages).text   = "${session.pages.size} page(s)"

        // Launch overlay — goes back to previous app automatically
        findViewById<Button>(R.id.btnLaunchOverlay).setOnClickListener {
            launchOverlay()
        }

        // Go back without launching
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun launchOverlay() {
        // Start the overlay service
        OverlayService.start(this, sessionId)

        Toast.makeText(
            this,
            "Overlay started! Switch to YouTube or any app — the toolbar will appear.",
            Toast.LENGTH_LONG
        ).show()

        // Go to home screen so user can open YouTube etc.
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(home)
    }
}
