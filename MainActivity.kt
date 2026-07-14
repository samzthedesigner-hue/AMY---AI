package com.amy.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * MainActivity - AMY v4.9.4 PRODUCTION entry point.
 * Wires layout, requests runtime permissions, runs the boot sequence, and
 * routes user input through AmyCore.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var amyCanvasView: AmyCanvasView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var btnCamera: ImageButton
    private lateinit var rvChat: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var lockIndicator: View

    private val chatAdapter = ChatAdapter()

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val permissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Amy)
        setContentView(R.layout.activity_main)

        bindViews()
        setupChatList()
        setupTabs()
        setupInputBar()

        requestNeededPermissions()
        runBoot()
    }

    private fun bindViews() {
        amyCanvasView = findViewById(R.id.amyCanvasView)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        btnCamera = findViewById(R.id.btnCamera)
        rvChat = findViewById(R.id.rvChat)
        tabLayout = findViewById(R.id.tabLayout)
        lockIndicator = findViewById(R.id.lockIndicator)
    }

    private fun setupChatList() {
        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = chatAdapter
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                AmyLogger.i("MainActivity", "Tab selected: ${tab?.text}")
                // Tab-specific content swapping would be wired here as the app grows
                // (Files / Vision / Widgets / Settings panels).
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupInputBar() {
        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotBlank()) {
                submitCommand(text)
                etInput.text.clear()
            }
        }

        btnMic.setOnClickListener {
            amyCanvasView.setState(AmyCanvasView.State.LISTENING)
            lifecycleScope.launch {
                val verified = AmyVoiceLock.verify(this@MainActivity)
                amyCanvasView.setState(AmyCanvasView.State.IDLE)
                if (verified) {
                    updateLockIndicator(unlocked = true)
                    chatAdapter.addMessage("Voice verified. AMY unlocked.", isUser = false)
                } else {
                    chatAdapter.addMessage("Voice not recognized.", isUser = false)
                }
            }
        }

        btnCamera.setOnClickListener {
            chatAdapter.addMessage("Camera capture directory: ${AmyVision.captureOutputDir()}", isUser = false)
        }
    }

    private fun submitCommand(text: String) {
        chatAdapter.addMessage(text, isUser = true)
        amyCanvasView.setState(AmyCanvasView.State.THINKING)
        lifecycleScope.launch {
            val response = AmyCore.route(this@MainActivity, text)
            amyCanvasView.setState(AmyCanvasView.State.SPEAKING)
            chatAdapter.addMessage(response.text, isUser = false)
            AmyTTS.speak(response.text)
            amyCanvasView.setState(AmyCanvasView.State.IDLE)
        }
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    private fun runBoot() {
        AmyTTS.init(this)
        lifecycleScope.launch {
            val bootResult = AmySteps.runBootSequence(this@MainActivity)
            updateLockIndicator(unlocked = bootResult.success && bootResult.deviceBound)
            val message = if (bootResult.success) {
                "AMY is online. ${bootResult.missingKeys}"
            } else {
                bootResult.message
            }
            chatAdapter.addMessage(message, isUser = false)
        }
    }

    private fun updateLockIndicator(unlocked: Boolean) {
        lockIndicator.setBackgroundColor(
            ContextCompat.getColor(this, if (unlocked) R.color.amy_unlocked else R.color.amy_locked)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            AmySteps.runShutdownSequence()
        }
    }

    /** Minimal inline chat adapter to keep this file self-contained under the 700-line/26-file caps. */
    private class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {
        private val messages = mutableListOf<Pair<String, Boolean>>() // text, isUser

        fun addMessage(text: String, isUser: Boolean) {
            messages.add(text to isUser)
            notifyItemInserted(messages.size - 1)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ChatViewHolder {
            val textView = TextView(parent.context).apply {
                setPadding(24, 16, 24, 16)
                textSize = 15f
            }
            return ChatViewHolder(textView)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val (text, isUser) = messages[position]
            holder.textView.text = text
            holder.textView.setTextColor(
                if (isUser) 0xFF00D9FF.toInt() else 0xFFB0BEC5.toInt()
            )
        }

        override fun getItemCount(): Int = messages.size

        class ChatViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }
}

/**
 * AmyApplication - minimal Application class referenced by AndroidManifest.xml.
 * Kept in this file (not separate) to stay within the 26-file cap.
 */
class AmyApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AmyKeys.init(this)
        AmyLogger.i("AmyApplication", "AMY v4.9.4 PRODUCTION starting")
    }
}
