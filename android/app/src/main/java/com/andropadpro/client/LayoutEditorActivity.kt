package com.andropadpro.client

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LayoutEditorActivity : AppCompatActivity() {
    
    private lateinit var prefs: android.content.SharedPreferences
    
    private var draggedView: View? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var selectedViewTag: String? = null
    private var isDragging = false
    
    private val viewPositions = mutableMapOf<String, Pair<Float, Float>>()
    private val viewSizes = mutableMapOf<String, Int>()
    
    private val viewTags = listOf(
        "left_stick", "right_stick", "dpad", "face_buttons",
        "btn_start", "btn_select", "btn_lb", "btn_rb",
        "lt_trigger", "rt_trigger"
    )
    
    private val defaultSizes = mapOf(
        "left_stick" to 110,
        "right_stick" to 110,
        "dpad" to 140,
        "face_buttons" to 140,
        "btn_start" to 80,
        "btn_select" to 80,
        "btn_lb" to 65,
        "btn_rb" to 65,
        "lt_trigger" to 55,
        "rt_trigger" to 55
    )
    
    private val defaultPositions = mapOf(
        "left_stick" to Pair(50f, 320f),
        "right_stick" to Pair(890f, 320f),
        "dpad" to Pair(45f, 150f),
        "face_buttons" to Pair(850f, 150f),
        "btn_start" to Pair(450f, 320f),
        "btn_select" to Pair(450f, 280f),
        "btn_lb" to Pair(85f, 55f),
        "btn_rb" to Pair(885f, 55f),
        "lt_trigger" to Pair(15f, 50f),
        "rt_trigger" to Pair(920f, 50f)
    )
    
    private lateinit var selectedViewText: TextView
    private lateinit var sizeBar: SeekBar
    private lateinit var sizeText: TextView
    private lateinit var editorContainer: ViewGroup
    private var topBarBottom = 0   // px — set after layout; drag is clamped to stay below this
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_editor)
        
        prefs = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        
        initViews()
        loadPositions()
    }
    
    private fun initViews() {
        editorContainer = findViewById(R.id.editor_container)
        selectedViewText = findViewById(R.id.selected_view_text)
        sizeBar = findViewById(R.id.size_bar)
        sizeText = findViewById(R.id.size_text)
        
        findViewById<Button>(R.id.btn_reset_layout).setOnClickListener {
            resetLayout()
            Toast.makeText(this, "Layout reset to default", Toast.LENGTH_SHORT).show()
        }
        
        findViewById<Button>(R.id.btn_save_layout).setOnClickListener {
            savePositions()
            Toast.makeText(this, "Layout saved", Toast.LENGTH_SHORT).show()
            finish()
        }
        
        findViewById<Button>(R.id.btn_cancel_layout).setOnClickListener {
            finish()
        }
        
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                selectedViewTag?.let { tag ->
                    val minSize = 30
                    val maxSize = 200
                    val newSize = minSize + (progress * (maxSize - minSize) / 100)
                    updateViewSize(tag, newSize)
                    sizeText.text = "Size: ${newSize}dp"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Measure the top bar height after layout so drag can avoid overlapping it
        val topBar = findViewById<View>(R.id.editor_top_bar)
        topBar.post { topBarBottom = topBar.bottom }

        setupDraggableViews()
    }
    
    private fun setupDraggableViews() {
        val viewIds = listOf(
            R.id.editor_left_stick to "left_stick",
            R.id.editor_right_stick to "right_stick",
            R.id.editor_dpad to "dpad",
            R.id.editor_face_buttons to "face_buttons",
            R.id.editor_btn_start to "btn_start",
            R.id.editor_btn_select to "btn_select",
            R.id.editor_btn_lb to "btn_lb",
            R.id.editor_btn_rb to "btn_rb",
            R.id.editor_lt_trigger to "lt_trigger",
            R.id.editor_rt_trigger to "rt_trigger"
        )
        
        val density = resources.displayMetrics.density
        
        for ((viewId, tag) in viewIds) {
            try {
                val view = findViewById<View>(viewId)
                view.tag = tag
                
                view.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            draggedView = v
                            isDragging  = false
                            val params  = v.layoutParams as android.widget.FrameLayout.LayoutParams
                            // getLocationInWindow gives position within activity content space
                            // (same coordinate space used when saving/loading positions)
                            val containerLoc = IntArray(2)
                            (v.parent as View).getLocationInWindow(containerLoc)
                            dragOffsetX = event.rawX - containerLoc[0] - params.leftMargin
                            dragOffsetY = event.rawY - containerLoc[1] - params.topMargin
                            selectView(tag, v)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (draggedView != null) {
                                isDragging = true
                                val parent = v.parent as View
                                val containerLoc = IntArray(2)
                                parent.getLocationInWindow(containerLoc)

                                val maxX = (parent.width  - v.width).coerceAtLeast(0)
                                // minY keeps buttons below the top toolbar; maxY is full container
                                val minY = (topBarBottom - containerLoc[1]).coerceAtLeast(0)
                                val maxY = (parent.height - v.height).coerceAtLeast(minY)

                                val newLeft = (event.rawX - containerLoc[0] - dragOffsetX)
                                    .toInt().coerceIn(0, maxX)
                                val newTop  = (event.rawY - containerLoc[1] - dragOffsetY)
                                    .toInt().coerceIn(minY, maxY)

                                val params = v.layoutParams as android.widget.FrameLayout.LayoutParams
                                params.leftMargin = newLeft
                                params.topMargin  = newTop
                                v.layoutParams    = params

                                viewPositions[tag] = Pair(newLeft / density, newTop / density)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            draggedView = null
                            isDragging  = false
                            true
                        }
                        else -> false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private fun selectView(tag: String, view: View) {
        selectedViewTag = tag
        
        val displayName = tag.replace("_", " ").replaceFirstChar { it.uppercase() }
        selectedViewText.text = "Selected: $displayName"
        
        val currentSize = viewSizes[tag] ?: defaultSizes[tag] ?: 90
        val minSize = 30
        val maxSize = 200
        val progress = ((currentSize - minSize) * 100 / (maxSize - minSize))
        sizeBar.progress = progress.coerceIn(0, 100)
        sizeText.text = "Size: ${currentSize}dp"
    }
    
    private fun updateViewSize(tag: String, size: Int) {
        val density = resources.displayMetrics.density
        for (i in 0 until editorContainer.childCount) {
            val child = editorContainer.getChildAt(i)
            if (child.tag == tag) {
                val pos = viewPositions[tag] ?: Pair(0f, 0f)
                val params = android.widget.FrameLayout.LayoutParams(size.dpToPx(), size.dpToPx())
                params.leftMargin = (pos.first * density).toInt()
                params.topMargin = (pos.second * density).toInt()
                child.layoutParams = params
                
                viewSizes[tag] = size
                break
            }
        }
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    private fun loadPositions() {
        val density = resources.displayMetrics.density
        
        for (tag in viewTags) {
            val savedX = prefs.getFloat("${tag}_x", -1f)
            val savedY = prefs.getFloat("${tag}_y", -1f)
            val savedSize = prefs.getInt("${tag}_size", -1)
            
            if (savedX >= 0 && savedY >= 0) {
                viewPositions[tag] = Pair(savedX, savedY)
            }
            
            if (savedSize > 0) {
                viewSizes[tag] = savedSize
            }
        }
        
        editorContainer.post {
            val containerW = editorContainer.width
            val containerH = editorContainer.height
            
            for (i in 0 until editorContainer.childCount) {
                val child = editorContainer.getChildAt(i)
                val tag = child.tag as? String ?: continue
                
                val savedSize = viewSizes[tag] ?: defaultSizes[tag] ?: 90
                val childW = savedSize.dpToPx()
                val childH = savedSize.dpToPx()
                
                var posX: Float
                var posY: Float
                
                if (viewPositions.containsKey(tag)) {
                    posX = viewPositions[tag]!!.first
                    posY = viewPositions[tag]!!.second
                } else {
                    posX = (containerW - childW) / 2f / density
                    posY = (containerH - childH) / 2f / density
                }

                // Clamp top so nothing starts hidden under the top bar
                val minYDp = topBarBottom / density
                posY = posY.coerceAtLeast(minYDp)
                
                val params = android.widget.FrameLayout.LayoutParams(childW, childH)
                params.leftMargin = (posX * density).toInt()
                params.topMargin = (posY * density).toInt()
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                child.layoutParams = params
                
                viewPositions[tag] = Pair(posX, posY)
                viewSizes[tag] = savedSize
            }
        }
    }
    
    private fun resetLayout() {
        prefs.edit().apply {
            for (tag in viewTags) {
                remove("${tag}_x")
                remove("${tag}_y")
                remove("${tag}_size")
            }
            apply()
        }
        viewPositions.clear()
        viewSizes.clear()
        
        val density = resources.displayMetrics.density
        
        editorContainer.post {
            val containerW = editorContainer.width
            val containerH = editorContainer.height
            val minYDp     = topBarBottom / density

            for (i in 0 until editorContainer.childCount) {
                val child = editorContainer.getChildAt(i)
                val tag = child.tag as? String ?: continue
                
                val defaultSize = defaultSizes[tag] ?: 90
                val childW = defaultSize.dpToPx()
                val childH = defaultSize.dpToPx()
                
                val posX = (containerW - childW) / 2f / density
                val posY = ((containerH - childH) / 2f / density).coerceAtLeast(minYDp)
                
                val params = android.widget.FrameLayout.LayoutParams(childW, childH)
                params.leftMargin = (posX * density).toInt()
                params.topMargin = (posY * density).toInt()
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                child.layoutParams = params
                
                viewPositions[tag] = Pair(posX, posY)
                viewSizes[tag] = defaultSize
            }
        }
        
        selectedViewTag = null
        selectedViewText.text = "Selected: None"
        sizeText.text = "Size: --"
    }
    
    private fun savePositions() {
        prefs.edit().apply {
            for ((tag, pos) in viewPositions) {
                putFloat("${tag}_x", pos.first)
                putFloat("${tag}_y", pos.second)
            }
            for ((tag, size) in viewSizes) {
                putInt("${tag}_size", size)
            }
            apply()
        }
    }
}
