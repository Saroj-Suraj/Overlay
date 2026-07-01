package com.overlaynote.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

enum class DrawingTool { PEN, PENCIL, HIGHLIGHTER, ERASER }

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Paint setup ──────────────────────────────────────────────
    private val paint = Paint().apply {
        isAntiAlias    = true
        isDither       = true
        style          = Paint.Style.STROKE
        strokeJoin     = Paint.Join.ROUND
        strokeCap      = Paint.Cap.ROUND
    }

    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
        xfermode    = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // ── State ────────────────────────────────────────────────────
    var tool      : DrawingTool = DrawingTool.PEN
    var drawColor : Int         = Color.WHITE
    var strokeWidth: Float      = 8f

    private var drawBitmap  : Bitmap?  = null
    private var drawCanvas  : Canvas?  = null
    private var path        : Path     = Path()
    private var lastX       : Float    = 0f
    private var lastY       : Float    = 0f

    // Undo/Redo stacks
    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val MAX_HISTORY = 30

    // ── Init canvas ──────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawBitmap == null || w != drawBitmap!!.width || h != drawBitmap!!.height) {
            val prev = drawBitmap
            drawBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawCanvas = Canvas(drawBitmap!!)
            if (prev != null && !prev.isRecycled) {
                drawCanvas!!.drawBitmap(prev, 0f, 0f, null)
                prev.recycle()
            }
        }
    }

    // ── Draw ─────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // Draw current path preview
        if (tool != DrawingTool.ERASER) {
            canvas.drawPath(path, configurePaint())
        }
    }

    // ── Touch ────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                saveSnapshot()
                path.reset()
                path.moveTo(x, y)
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val midX = (lastX + x) / 2f
                val midY = (lastY + y) / 2f
                path.quadTo(lastX, lastY, midX, midY)
                lastX = x; lastY = y

                if (tool == DrawingTool.ERASER) {
                    eraserPaint.strokeWidth = strokeWidth * 3f
                    drawCanvas?.drawPath(path, eraserPaint)
                    path.reset()
                    path.moveTo(x, y)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                path.lineTo(x, y)
                if (tool != DrawingTool.ERASER) {
                    drawCanvas?.drawPath(path, configurePaint())
                }
                path.reset()
                invalidate()
            }
        }
        return true
    }

    private fun configurePaint(): Paint {
        return paint.apply {
            when (tool) {
                DrawingTool.PEN -> {
                    color       = drawColor
                    strokeWidth = this@DrawingView.strokeWidth
                    alpha       = 255
                    maskFilter  = null
                    xfermode    = null
                }
                DrawingTool.PENCIL -> {
                    color       = drawColor
                    strokeWidth = this@DrawingView.strokeWidth * 0.7f
                    alpha       = 130
                    maskFilter  = BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL)
                    xfermode    = null
                }
                DrawingTool.HIGHLIGHTER -> {
                    color       = drawColor
                    strokeWidth = this@DrawingView.strokeWidth * 5f
                    alpha       = 80
                    maskFilter  = null
                    xfermode    = null
                }
                DrawingTool.ERASER -> {
                    // handled separately
                }
            }
        }
    }

    // ── Undo / Redo ──────────────────────────────────────────────
    private fun saveSnapshot() {
        drawBitmap?.let {
            val snap = it.copy(Bitmap.Config.ARGB_8888, false)
            undoStack.addLast(snap)
            if (undoStack.size > MAX_HISTORY) {
                undoStack.removeFirst().recycle()
            }
            redoStack.forEach { b -> b.recycle() }
            redoStack.clear()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = drawBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        current?.let { redoStack.addLast(it) }

        val prev = undoStack.removeLast()
        drawCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawCanvas?.drawBitmap(prev, 0f, 0f, null)
        prev.recycle()
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = drawBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        current?.let { undoStack.addLast(it) }

        val next = redoStack.removeLast()
        drawCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawCanvas?.drawBitmap(next, 0f, 0f, null)
        next.recycle()
        invalidate()
    }

    fun clearCanvas() {
        saveSnapshot()
        drawCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
    }

    // ── Export ───────────────────────────────────────────────────
    fun getBitmap(): Bitmap? = drawBitmap?.copy(Bitmap.Config.ARGB_8888, false)

    fun loadBitmap(bmp: Bitmap?) {
        if (bmp == null) return
        drawCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawCanvas?.drawBitmap(bmp, 0f, 0f, null)
        invalidate()
    }

    fun isEmpty(): Boolean {
        val bmp = drawBitmap ?: return true
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return pixels.all { it == 0 }
    }
}
