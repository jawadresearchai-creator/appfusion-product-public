package com.appfusion.product

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime: AndroidDocumentRuntime
        get() = (application as FusionApplication).documentRuntime

    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var saveButton: Button
    private lateinit var searchButton: Button
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        setBusy("Opening encrypted workspace…")
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { runtime.start() } }
                .onSuccess { report ->
                    saveButton.isEnabled = true
                    searchButton.isEnabled = true
                    status.text = if (report.isClean) {
                        "Secure workspace ready · ${report.verifiedActiveDocuments} verified document(s)"
                    } else {
                        "Workspace opened with ${report.issues.size} recovery issue(s)"
                    }
                    status.setTextColor(Color.rgb(32, 92, 72))
                }
                .onFailure(::showFailure)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(30))
            setBackgroundColor(Color.rgb(244, 247, 245))
        }
        page.addView(TextView(this).apply {
            text = "AppFusion"
            textSize = 32f
            setTextColor(Color.rgb(21, 60, 53))
        })
        page.addView(TextView(this).apply {
            text = "Private documents · activity · reminders"
            textSize = 15f
            setTextColor(Color.rgb(74, 89, 84))
            setPadding(0, dp(4), 0, dp(22))
        })
        page.addView(sectionTitle("Create encrypted document"))
        titleInput = input("Title", singleLine = true).apply { id = R.id.document_title }
        bodyInput = input("Write a private note…", singleLine = false).apply {
            id = R.id.document_body
            minLines = 5
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        page.addView(titleInput)
        page.addView(bodyInput)
        saveButton = primaryButton("Encrypt & save").apply {
            id = R.id.save_document
            isEnabled = false
            setOnClickListener { saveDocument() }
        }
        page.addView(saveButton)
        page.addView(sectionTitle("Find your work").apply { setPadding(0, dp(26), 0, dp(8)) })
        searchInput = input("Search documents", singleLine = true).apply { id = R.id.search_query }
        page.addView(searchInput)
        searchButton = primaryButton("Search").apply {
            id = R.id.search_documents
            isEnabled = false
            setOnClickListener { searchDocuments() }
        }
        page.addView(searchButton)
        status = TextView(this).apply {
            id = R.id.status_text
            textSize = 14f
            setPadding(0, dp(18), 0, dp(10))
        }
        page.addView(status)
        results = LinearLayout(this).apply {
            id = R.id.search_results
            orientation = LinearLayout.VERTICAL
        }
        page.addView(results)
        return ScrollView(this).apply {
            addView(page)
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    top = insets.systemWindowInsetTop
                    bottom = insets.systemWindowInsetBottom
                }
                page.setPadding(dp(22), dp(30) + top, dp(22), dp(30) + bottom)
                insets
            }
        }
    }

    private fun saveDocument() {
        val title = titleInput.text.toString().trim()
        val body = bodyInput.text.toString().trim()
        if (title.isEmpty() || body.isEmpty()) {
            showFailure(IllegalArgumentException("Enter both a title and document text."))
            return
        }
        setBusy("Encrypting and saving…")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    runtime.createDocument("document-${UUID.randomUUID()}", title, body)
                }
            }.onSuccess {
                titleInput.text.clear()
                bodyInput.text.clear()
                searchInput.setText(title)
                status.text = "Saved securely. Search or reopen it below."
                status.setTextColor(Color.rgb(32, 92, 72))
                renderResults(runtime.searchDocuments(title))
            }.onFailure(::showFailure)
        }
    }

    private fun searchDocuments() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            renderResults(emptyList())
            status.text = "Enter a search term."
            return
        }
        renderResults(runtime.searchDocuments(query))
    }

    private fun renderResults(items: List<DocumentListItem>) {
        results.removeAllViews()
        status.text = if (items.isEmpty()) "No matching documents." else "${items.size} result(s)"
        items.forEach { item ->
            results.addView(Button(this).apply {
                id = R.id.search_result_item
                text = "${item.title}\n${item.label}"
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setOnClickListener { openDocument(item) }
            })
        }
    }

    private fun openDocument(item: DocumentListItem) {
        setBusy("Decrypting ${item.title}…")
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { runtime.readDocument(item.id) } }
                .onSuccess { body ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(item.title)
                        .setMessage(body ?: "The encrypted payload is unavailable.")
                        .setPositiveButton("Done", null)
                        .show()
                    status.text = "Opened verified encrypted document."
                }
                .onFailure(::showFailure)
        }
    }

    private fun input(hintText: String, singleLine: Boolean) = EditText(this).apply {
        hint = hintText
        setSingleLine(singleLine)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun primaryButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(53, 104, 89))
        layoutParams = LinearLayout.LayoutParams(-1, dp(52))
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 19f
        setTextColor(Color.rgb(21, 60, 53))
        setPadding(0, 0, 0, dp(8))
    }

    private fun setBusy(message: String) {
        status.text = message
        status.setTextColor(Color.rgb(74, 89, 84))
    }

    private fun showFailure(failure: Throwable) {
        status.text = failure.message ?: "The operation failed."
        status.setTextColor(Color.rgb(166, 51, 47))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
