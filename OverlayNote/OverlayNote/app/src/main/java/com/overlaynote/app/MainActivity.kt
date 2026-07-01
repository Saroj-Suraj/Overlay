package com.overlaynote.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var fab: FloatingActionButton
    private lateinit var adapter: SessionAdapter
    private val sessions get() = SessionManager.getSessions(this)

    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerSessions)
        emptyView    = findViewById(R.id.emptyView)
        fab          = findViewById(R.id.fab)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SessionAdapter(
            onOpen   = { session -> openSession(session) },
            onDelete = { session -> deleteSession(session) }
        )
        recyclerView.adapter = adapter

        fab.setOnClickListener { showNewSessionDialog() }

        // Stop overlay service if running when returning to home
        OverlayService.stop(this)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val list = sessions
        adapter.submitList(list)
        emptyView.visibility    = if (list.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (list.isEmpty()) View.GONE   else View.VISIBLE
    }

    private fun showNewSessionDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_new_session, null)
        val etSubject = view.findViewById<EditText>(R.id.etSubject)
        val etTopic   = view.findViewById<EditText>(R.id.etTopic)

        AlertDialog.Builder(this)
            .setTitle("New Lecture Session")
            .setView(view)
            .setPositiveButton("Start Writing") { _, _ ->
                val subject = etSubject.text.toString().trim()
                val topic   = etTopic.text.toString().trim()
                if (subject.isEmpty()) {
                    Toast.makeText(this, "Please enter a subject", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val session = Session(
                    id      = UUID.randomUUID().toString(),
                    subject = subject,
                    topic   = topic.ifEmpty { "General Notes" },
                    date    = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()),
                    created = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
                    pages   = mutableListOf()
                )
                SessionManager.saveSession(this, session)
                openSession(session)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSession(session: Session) {
        // Check overlay permission first
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission(session)
            return
        }
        val intent = Intent(this, SessionActivity::class.java)
        intent.putExtra("session_id", session.id)
        startActivity(intent)
    }

    private fun requestOverlayPermission(pendingSession: Session? = null) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("OverlayNote needs 'Display over other apps' permission to write notes over YouTube and other apps.\n\nTap OK to open settings, then enable it for OverlayNote.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSession(session: Session) {
        AlertDialog.Builder(this)
            .setTitle("Delete Session")
            .setMessage("Delete \"${session.subject}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                SessionManager.deleteSession(this, session.id)
                refreshList()
                Toast.makeText(this, "Session deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission granted! Tap a session to start.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied — overlay won't work without it.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ─── Session RecyclerView Adapter ────────────────────────────────
class SessionAdapter(
    private val onOpen:   (Session) -> Unit,
    private val onDelete: (Session) -> Unit
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    private var list = listOf<Session>()

    fun submitList(newList: List<Session>) {
        list = newList
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvSubject  = v.findViewById<TextView>(R.id.tvSubject)
        val tvTopic    = v.findViewById<TextView>(R.id.tvTopic)
        val tvDate     = v.findViewById<TextView>(R.id.tvDate)
        val tvPages    = v.findViewById<TextView>(R.id.tvPages)
        val btnDelete  = v.findViewById<ImageButton>(R.id.btnDelete)
        val card       = v.findViewById<androidx.cardview.widget.CardView>(R.id.card)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = list[position]
        h.tvSubject.text = s.subject
        h.tvTopic.text   = s.topic
        h.tvDate.text    = s.date
        h.tvPages.text   = "${s.pages.size} page${if (s.pages.size != 1) "s" else ""}"
        h.card.setOnClickListener      { onOpen(s) }
        h.btnDelete.setOnClickListener { onDelete(s) }
    }

    override fun getItemCount() = list.size
}
