package com.overlaynote.app

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import android.graphics.PixelFormat
import android.view.WindowManager.LayoutParams.*

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP          = "com.overlaynote.STOP"
        const val ACTION_TOGGLE_DRAW   = "com.overlaynote.TOGGLE_DRAW"
        const val EXTRA_SESSION_ID     = "session_id"
        const val CHANNEL_ID           = "overlay_channel"
        const val NOTIF_ID             = 1

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, OverlayService::class.java)
            intent.putExtra(EXTRA_SESSION_ID, sessionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager : WindowManager
    private lateinit var overlayView   : View
    private lateinit var drawingView   : DrawingView
    private lateinit var toolbar       : View

    // Toolbar drag state
    private var tbX = 0f; private var tbY = 0f
    private var tbInitX = 0f; private var tbInitY = 0f
    private var tbTouchX = 0f; private var tbTouchY = 0f

    private var sessionId   = ""
    private var currentPage = 0
    private var pages       = mutableListOf<Bitmap?>()
    private var drawMode    = true   // false = view/scroll mode

    // Colors
    private val colors = listOf(
        Color.WHITE, Color.parseColor("#4A8FE7"), Color.parseColor("#2ECFB0"),
        Color.parseColor("#F5A623"), Color.parseColor("#E05C5C"),
        Color.parseColor("#C47EF5"), Color.parseColor("#F5E24A"),
        Color.parseColor("#FF8A50")
    )
    private var colorIdx = 0

    // Sizes
    private val sizes = listOf(4f, 8f, 14f, 22f, 36f)
    private var sizeIdx = 1

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: ""

        // Load existing pages for session
        val session = SessionManager.getSession(this, sessionId)
        pages = MutableList(maxOf(1, session?.pages?.size ?: 1)) { null }
        // Load saved bitmaps
        session?.pages?.forEachIndexed { i, pageId ->
            pages[i] = SessionManager.loadPageBitmap(this, sessionId, pageId)
        }

        startForeground(NOTIF_ID, buildNotification())
        buildOverlay()
        return START_STICKY
    }

    // ── Build the overlay UI ──────────────────────────────────────
    private fun buildOverlay() {
        val inflater = LayoutInflater.from(this)

        // Full-screen transparent canvas
        overlayView = inflater.inflate(R.layout.overlay_main, null)
        drawingView = overlayView.findViewById(R.id.drawingView)
        val overlayParams = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                TYPE_APPLICATION_OVERLAY else TYPE_PHONE,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, overlayParams)

        // Load first page
        drawingView.post {
            pages[currentPage]?.let { drawingView.loadBitmap(it) }
        }

        // Floating toolbar
        toolbar = inflater.inflate(R.layout.overlay_toolbar, null)
        val tbParams = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                TYPE_APPLICATION_OVERLAY else TYPE_PHONE,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        tbParams.gravity = Gravity.TOP or Gravity.START
        tbParams.x = 24; tbParams.y = 200
        windowManager.addView(toolbar, tbParams)

        bindToolbarButtons(tbParams)
        updateToolbarState()
    }

    // ── Toolbar button bindings ───────────────────────────────────
    private fun bindToolbarButtons(tbParams: WindowManager.LayoutParams) {

        // ── Drag handle ──────────────────────────────────────────
        toolbar.findViewById<View>(R.id.dragHandle).setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    tbInitX = tbParams.x.toFloat(); tbInitY = tbParams.y.toFloat()
                    tbTouchX = e.rawX; tbTouchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    tbParams.x = (tbInitX + e.rawX - tbTouchX).toInt()
                    tbParams.y = (tbInitY + e.rawY - tbTouchY).toInt()
                    windowManager.updateViewLayout(toolbar, tbParams)
                    true
                }
                else -> false
            }
        }

        // ── Tools ────────────────────────────────────────────────
        toolbar.findViewById<ImageButton>(R.id.btnPen).setOnClickListener {
            drawingView.tool = DrawingTool.PEN; updateToolbarState()
        }
        toolbar.findViewById<ImageButton>(R.id.btnPencil).setOnClickListener {
            drawingView.tool = DrawingTool.PENCIL; updateToolbarState()
        }
        toolbar.findViewById<ImageButton>(R.id.btnHighlighter).setOnClickListener {
            drawingView.tool = DrawingTool.HIGHLIGHTER; updateToolbarState()
        }
        toolbar.findViewById<ImageButton>(R.id.btnEraser).setOnClickListener {
            drawingView.tool = DrawingTool.ERASER; updateToolbarState()
        }

        // ── Color cycle ──────────────────────────────────────────
        toolbar.findViewById<View>(R.id.btnColor).setOnClickListener {
            colorIdx = (colorIdx + 1) % colors.size
            drawingView.drawColor = colors[colorIdx]
            updateToolbarState()
        }

        // ── Size cycle ───────────────────────────────────────────
        toolbar.findViewById<View>(R.id.btnSize).setOnClickListener {
            sizeIdx = (sizeIdx + 1) % sizes.size
            drawingView.strokeWidth = sizes[sizeIdx]
            updateToolbarState()
        }

        // ── Undo / Redo ──────────────────────────────────────────
        toolbar.findViewById<ImageButton>(R.id.btnUndo).setOnClickListener  { drawingView.undo()  }
        toolbar.findViewById<ImageButton>(R.id.btnRedo).setOnClickListener  { drawingView.redo()  }
        toolbar.findViewById<ImageButton>(R.id.btnClear).setOnClickListener { drawingView.clearCanvas() }

        // ── Draw / View mode toggle ───────────────────────────────
        toolbar.findViewById<ImageButton>(R.id.btnMode).setOnClickListener {
            drawMode = !drawMode
            val lp = overlayView.layoutParams as WindowManager.LayoutParams
            if (drawMode) {
                lp.flags = FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS
            } else {
                lp.flags = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS
            }
            windowManager.updateViewLayout(overlayView, lp)
            updateToolbarState()
            Toast.makeText(this, if (drawMode) "Draw mode" else "View mode — scroll freely", Toast.LENGTH_SHORT).show()
        }

        // ── New page ─────────────────────────────────────────────
        toolbar.findViewById<ImageButton>(R.id.btnNewPage).setOnClickListener {
            saveCurrentPage()
            pages.add(null)
            currentPage = pages.size - 1
            drawingView.clearCanvas()
            updatePageIndicator()
            Toast.makeText(this, "Page ${currentPage + 1} of ${pages.size}", Toast.LENGTH_SHORT).show()
        }

        // ── Prev / Next page ─────────────────────────────────────
        toolbar.findViewById<ImageButton>(R.id.btnPrevPage).setOnClickListener {
            if (currentPage > 0) {
                saveCurrentPage()
                currentPage--
                drawingView.post {
                    drawingView.clearCanvas()
                    pages[currentPage]?.let { drawingView.loadBitmap(it) }
                }
                updatePageIndicator()
            }
        }
        toolbar.findViewById<ImageButton>(R.id.btnNextPage).setOnClickListener {
            if (currentPage < pages.size - 1) {
                saveCurrentPage()
                currentPage++
                drawingView.post {
                    drawingView.clearCanvas()
                    pages[currentPage]?.let { drawingView.loadBitmap(it) }
                }
                updatePageIndicator()
            }
        }

        // ── Save ─────────────────────────────────────────────────
        toolbar.findViewById<ImageButton>(R.id.btnSave).setOnClickListener {
            saveCurrentPage()
            val bmp = drawingView.getBitmap()
            if (bmp != null) {
                ExportHelper.savePng(this, bmp, sessionId, currentPage)
                Toast.makeText(this, "Page saved to Gallery ✓", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Close overlay ────────────────────────────────────────
        toolbar.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            saveAllPages()
            stopSelf()
            // Return to launcher
            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(home)
        }

        updatePageIndicator()
    }

    private fun saveCurrentPage() {
        pages[currentPage] = drawingView.getBitmap()
    }

    private fun saveAllPages() {
        saveCurrentPage()
        SessionManager.savePages(this, sessionId, pages)
    }

    private fun updateToolbarState() {
        val btnPen  = toolbar.findViewById<ImageButton>(R.id.btnPen)
        val btnPen2 = toolbar.findViewById<ImageButton>(R.id.btnPencil)
        val btnHL   = toolbar.findViewById<ImageButton>(R.id.btnHighlighter)
        val btnEr   = toolbar.findViewById<ImageButton>(R.id.btnEraser)
        val btnMode = toolbar.findViewById<ImageButton>(R.id.btnMode)
        val colorDot= toolbar.findViewById<View>(R.id.btnColor)
        val sizeDot = toolbar.findViewById<View>(R.id.btnSize)

        val activeAlpha   = 1.0f
        val inactiveAlpha = 0.4f

        btnPen.alpha  = if (drawingView.tool == DrawingTool.PEN)         activeAlpha else inactiveAlpha
        btnPen2.alpha = if (drawingView.tool == DrawingTool.PENCIL)      activeAlpha else inactiveAlpha
        btnHL.alpha   = if (drawingView.tool == DrawingTool.HIGHLIGHTER) activeAlpha else inactiveAlpha
        btnEr.alpha   = if (drawingView.tool == DrawingTool.ERASER)      activeAlpha else inactiveAlpha

        colorDot.setBackgroundColor(colors[colorIdx])
        val sizePx = (sizes[sizeIdx] + 16).toInt()
        val sdp = sizeDot.layoutParams
        sdp.width = sizePx; sdp.height = sizePx
        sizeDot.layoutParams = sdp

        btnMode.alpha = if (drawMode) 1.0f else 0.5f
    }

    private fun updatePageIndicator() {
        toolbar.findViewById<TextView>(R.id.tvPageIndicator)?.text =
            "${currentPage + 1}/${pages.size}"
    }

    // ── Notification ─────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val stopIntent   = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent   = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OverlayNote Active")
            .setContentText("Writing overlay is on — tap to manage")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "OverlayNote", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Overlay writing service" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveAllPages()
        if (::overlayView.isInitialized) runCatching { windowManager.removeView(overlayView) }
        if (::toolbar.isInitialized)     runCatching { windowManager.removeView(toolbar) }
    }
}
