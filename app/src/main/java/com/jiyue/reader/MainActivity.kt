package com.jiyue.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private val store by lazy { DocumentStore(this) }
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private lateinit var root: LinearLayout
    private var current: StoredDocument? = null
    private var webView: WebView? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var showFavoritesOnly = false
    private var fontScale = 1.0f
    private var darkMode = false
    private var desktopMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Surface
        window.navigationBarColor = Surface
        handleIncomingIntent(intent) ?: showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent) ?: showHome()
    }

    private fun handleIncomingIntent(intent: Intent): Unit? {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return null

        try {
            val doc = store.importUri(uri)
            showReader(doc)
        } catch (error: Exception) {
            Toast.makeText(this, "无法打开文件：${error.message}", Toast.LENGTH_LONG).show()
            showHome()
        }
        return Unit
    }

    private fun showHome() {
        current = null
        webView = null
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Surface)
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        setContentView(root)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "即阅"
                    textSize = 31f
                    setTextColor(Ink)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = "手机里的本地网页与文档预览器"
                    textSize = 13f
                    setTextColor(Muted)
                    setPadding(0, dp(7), 0, 0)
                })
            })
            addView(pillButton(if (showFavoritesOnly) "全部" else "收藏") {
                showFavoritesOnly = !showFavoritesOnly
                showHome()
            })
        })

        root.addView(openPanel().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
            }
        })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(20))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val docs = store.list().filter { !showFavoritesOnly || it.favorite }
        list.addView(sectionHeader(if (showFavoritesOnly) "收藏文件" else "最近打开", "${docs.size} 个"))
        list.addView(segmentedControl())
        if (docs.isEmpty()) {
            list.addView(emptyState().apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                }
            })
        } else {
            docs.forEach { doc -> list.addView(documentRow(doc)) }
        }
        list.addView(authorFeedbackLink().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(18)
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showReader(doc: StoredDocument) {
        current = doc
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Surface)
        }
        setContentView(root)

        root.addView(readerTopBar(doc))

        val web = WebView(this)
        val entryKind = doc.entryKind()
        val localScriptsEnabled = isLocalScriptEnabled(doc)
        webView = web
        web.settings.apply {
            allowFileAccess = true
            allowContentAccess = localScriptsEnabled
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = false
            builtInZoomControls = true
            displayZoomControls = false
            domStorageEnabled = false
            javaScriptEnabled = entryKind == DocKind.HTML && localScriptsEnabled
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
            textZoom = (100 * fontScale).toInt()
        }
        if (localScriptsEnabled) {
            web.addJavascriptInterface(LocalToolBridge(this), "JiyueBridge")
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase(Locale.US)
                return scheme != null && scheme !in LocalSchemes
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val scheme = request?.url?.scheme?.lowercase(Locale.US)
                return if (scheme == "http" || scheme == "https") emptyWebResponse() else null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (localScriptsEnabled) view?.evaluateJavascript(LocalDownloadBridgeScript, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                return try {
                    startActivityForResult(intent, HtmlFileChooserRequest)
                    true
                } catch (_: ActivityNotFoundException) {
                    pendingFileChooser = null
                    Toast.makeText(this@MainActivity, "没有可用的文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
        web.setBackgroundColor(if (darkMode) Color.rgb(20, 22, 24) else Color.rgb(255, 255, 255))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(readerBottomBar(doc))

        if (entryKind == DocKind.HTML) {
            web.loadUrl(File(doc.entryPath).toURI().toString())
        } else {
            val html = Renderer.renderDocument(File(doc.entryPath).readText(), entryKind, darkMode)
            web.loadDataWithBaseURL(File(doc.entryPath).parentFile?.toURI().toString(), html, "text/html", "UTF-8", null)
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/markdown", "text/x-markdown", "text/html", "application/xhtml+xml", "text/plain", "application/zip"))
        }
        try {
            startActivityForResult(intent, OpenDocumentRequest)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OpenDocumentRequest && resultCode == RESULT_OK && data?.data != null) {
            handleIncomingIntent(Intent(Intent.ACTION_VIEW, data.data))
        } else if (requestCode == HtmlFileChooserRequest) {
            val callback = pendingFileChooser ?: return
            pendingFileChooser = null
            callback.onReceiveValue(parseFileChooserResult(resultCode, data))
        }
    }

    private fun documentRow(doc: StoredDocument): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, dp(12))
            elevation = dp(1).toFloat()
            setOnClickListener { showReader(doc) }
        }
        row.addView(kindBadge(doc.kind).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(12)
            }
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = doc.name
                textSize = 16.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(Ink)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "${doc.kind.label} · ${dateFormat.format(Date(doc.updatedAt))} · ${fileSize(doc.originalPath)}"
                textSize = 12.5f
                setTextColor(Muted)
                setPadding(0, dp(6), 0, 0)
            })
        })
        row.addView(TextView(this).apply {
            text = if (doc.favorite) "★" else "›"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(if (doc.favorite) Accent else SoftInk)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(44))
        })
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        return row
    }

    private fun showToc() {
        val doc = current ?: return
        if (doc.entryKind() == DocKind.HTML) {
            Toast.makeText(this, "HTML 目录将在后续版本增强", Toast.LENGTH_SHORT).show()
            return
        }
        val headings = Renderer.extractHeadings(File(doc.entryPath).readText())
        if (headings.isEmpty()) {
            Toast.makeText(this, "没有检测到标题", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(headings.map { "${"  ".repeat(it.level - 1)}${it.title}" }.toTypedArray()) { _, index ->
                webView?.evaluateJavascript("location.hash='${headings[index].id}'", null)
            }
            .show()
    }

    private fun showDisplayOptions() {
        val doc = current ?: return
        val options = if (doc.entryKind() == DocKind.HTML) {
            arrayOf(
                if (desktopMode) "切换为手机模式" else "切换为桌面模式",
                if (isLocalScriptEnabled(doc)) "关闭本地交互" else "启用本地交互",
                "缩放比例"
            )
        } else {
            arrayOf(if (darkMode) "浅色模式" else "深色模式", "缩放比例")
        }
        AlertDialog.Builder(this)
            .setTitle("显示")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "切换为手机模式", "切换为桌面模式" -> {
                        desktopMode = !desktopMode
                        showReader(doc)
                    }
                    "关闭本地交互", "启用本地交互" -> toggleLocalScripts(doc)
                    "深色模式", "浅色模式" -> {
                        darkMode = !darkMode
                        showReader(doc)
                    }
                    "缩放比例" -> askZoom(doc)
                }
            }
            .show()
    }

    private fun askZoom(doc: StoredDocument) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "100"
            setText((fontScale * 100).toInt().toString())
        }
        AlertDialog.Builder(this)
            .setTitle("缩放比例")
            .setView(input)
            .setPositiveButton("应用") { _, _ ->
                fontScale = (input.text.toString().toIntOrNull() ?: 100).coerceIn(70, 180) / 100f
                showReader(doc)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleLocalScripts(doc: StoredDocument) {
        if (isLocalScriptEnabled(doc)) {
            setLocalScriptEnabled(doc, false)
            showReader(doc)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("启用本地交互？")
            .setMessage("即阅会允许当前文件运行 JavaScript、读取你选择的本地文件，并保存页面生成的下载文件，但仍拦截网络请求。")
            .setPositiveButton("启用") { _, _ ->
                setLocalScriptEnabled(doc, true)
                showReader(doc)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoreActions() {
        val doc = current ?: return
        AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(arrayOf("文件信息", "关于即阅", "分享原文件", "删除本地记录")) { _, which ->
                when (which) {
                    0 -> showDocumentInfo(doc)
                    1 -> showAboutJiyue()
                    2 -> shareFile(doc)
                    3 -> {
                        store.delete(doc.id)
                        showHome()
                    }
                }
            }
            .show()
    }

    private fun shareFile(doc: StoredDocument) {
        val file = File(doc.originalPath)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = URLConnection.guessContentTypeFromName(file.name) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享文件"))
    }

    private fun showDocumentInfo(doc: StoredDocument) {
        AlertDialog.Builder(this)
            .setTitle(doc.name)
            .setMessage(
                listOf(
                    "类型：${doc.kind.label}",
                    "大小：${fileSize(doc.originalPath)}",
                    "打开时间：${dateFormat.format(Date(doc.updatedAt))}",
                    "收藏：${if (doc.favorite) "是" else "否"}",
                    "本地文件：${doc.originalPath}"
                ).joinToString("\n")
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showAboutJiyue() {
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(420)
            setPadding(dp(22), dp(30), dp(22), dp(26))
            background = rounded(Panel, dp(24))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(logoMark().apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        rightMargin = dp(12)
                    }
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = "即阅"
                        textSize = 23f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                        setTextColor(Ink)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "Markdown / HTML 本地阅读"
                        textSize = 12.5f
                        setTextColor(SoftInk)
                        setPadding(0, dp(5), 0, 0)
                    })
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = "即开即读，安静预览。\n即阅只负责打开本地 Markdown、HTML、TXT 与文档 ZIP，不上传文件，也不打扰阅读。HTML 默认启用本地交互，并拦截网络请求。"
                textSize = 13.5f
                setTextColor(Muted)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(20), 0, dp(20))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedStroke(Color.rgb(246, 244, 239), dp(18), Hairline)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                }
                addView(contactRow("网站", "fengdaoai.com", "打开") { openAuthorWebsite() })
                addView(contactDivider())
                addView(contactRow("公众号", AuthorAccount, "复制") { copyText("公众号", AuthorAccount) })
            })
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
            })
            addView(TextView(this@MainActivity).apply {
                text = "关闭"
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(SecondaryInk)
                background = rounded(Control, dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(164), dp(44)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8)
                }
                setOnClickListener { dialog.dismiss() }
            })
        }

        dialog = AlertDialog.Builder(this)
            .setView(content)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun openAuthorWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AuthorWebsite)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "已复制$label", Toast.LENGTH_SHORT).show()
    }

    private fun openPanel(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(16), dp(16))
            background = roundedStroke(Panel, dp(20), Hairline)
            addView(logoMark().apply {
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                    rightMargin = dp(14)
                }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "打开文件"
                    textSize = 20f
                    setTextColor(Ink)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = "MD / HTML / TXT / ZIP，仅本地保存"
                    textSize = 12.5f
                    setTextColor(Muted)
                    setPadding(0, dp(8), 0, 0)
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = "选择"
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(Accent, dp(15))
                layoutParams = LinearLayout.LayoutParams(dp(74), dp(44)).apply {
                    leftMargin = dp(12)
                }
                setOnClickListener { openFilePicker() }
            })
        }

    private fun logoMark(): View =
        object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val bookmark = Path()

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val outer = minOf(width, height) * 0.43f
                val inner = outer * 0.62f

                paint.style = Paint.Style.FILL
                paint.color = Accent
                canvas.drawCircle(cx, cy, outer, paint)
                paint.color = Color.rgb(246, 239, 227)
                canvas.drawCircle(cx, cy, inner, paint)

                paint.color = Accent
                paint.strokeWidth = dp(3).toFloat()
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(cx - inner * 0.45f, cy - inner * 0.12f, cx + inner * 0.45f, cy - inner * 0.12f, paint)
                canvas.drawLine(cx - inner * 0.45f, cy + inner * 0.22f, cx + inner * 0.2f, cy + inner * 0.22f, paint)

                bookmark.reset()
                bookmark.moveTo(cx, cy + inner * 0.7f)
                bookmark.lineTo(cx + inner * 0.48f, cy + inner * 1.45f)
                bookmark.lineTo(cx, cy + inner * 1.18f)
                bookmark.lineTo(cx - inner * 0.48f, cy + inner * 1.45f)
                bookmark.close()
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(49, 95, 67)
                canvas.drawPath(bookmark, paint)
            }
        }

    private fun authorFeedbackLink(): View =
        TextView(this).apply {
            text = "作者与反馈"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(SoftInk)
            background = rounded(Color.TRANSPARENT, dp(12))
            setOnClickListener { showAboutJiyue() }
        }

    private fun sectionHeader(title: String, count: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTextColor(Ink)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = count
                textSize = 12.5f
                setTextColor(Muted)
            })
        }

    private fun segmentedControl(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.rgb(238, 236, 231), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(12)
                bottomMargin = dp(14)
            }
            addView(segment("最近", !showFavoritesOnly) {
                showFavoritesOnly = false
                showHome()
            })
            addView(segment("收藏", showFavoritesOnly) {
                showFavoritesOnly = true
                showHome()
            })
        }

    private fun segment(text: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (active) Ink else Muted)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = if (active) rounded(Color.WHITE, dp(11)) else null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun emptyState(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(42), dp(24), dp(42))
            background = roundedStroke(Panel, dp(18), Hairline)
            addView(TextView(this@MainActivity).apply {
                text = if (showFavoritesOnly) "暂无收藏" else "还没有打开记录"
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Ink)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (showFavoritesOnly) "在阅读页点星标后，文件会出现在这里。" else "从聊天、文件管理器或系统分享面板选择即阅，即可开始预览。"
                textSize = 14f
                gravity = Gravity.START
                setTextColor(Muted)
                setPadding(0, dp(10), 0, dp(18))
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(primaryButton("选择文件") { openFilePicker() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            })
        }

    private fun readerTopBar(doc: StoredDocument): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Surface)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton("‹", "返回") { showHome() })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(10), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = doc.name
                        textSize = 16f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        setTextColor(Ink)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = buildReaderSubtitle(doc)
                        textSize = 12f
                        setTextColor(Muted)
                        setPadding(0, dp(5), 0, 0)
                    })
                })
                addView(kindTextBadge(doc.kind))
            })
        }

    private fun readerBottomBar(doc: StoredDocument): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Surface)
            addView(navAction(if (doc.favorite) "★\n已藏" else "☆\n收藏") {
                current = store.toggleFavorite(doc.id)
                showReader(current!!)
            })
            addView(navAction("☰\n目录") { showToc() })
            addView(navAction("Aa\n显示") { showDisplayOptions() })
            addView(navAction("↗\n分享") { shareFile(doc) })
            addView(navAction("⋯\n更多") { showMoreActions() })
        }

    private fun primaryButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Accent, dp(14))
            setOnClickListener { onClick() }
        }

    private fun pillButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(SecondaryInk)
            background = rounded(Control, dp(16))
            minWidth = dp(68)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
            setOnClickListener { onClick() }
        }

    private fun iconButton(text: String, description: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            contentDescription = description
            textSize = if (text == "Aa") 15f else 21f
            gravity = Gravity.CENTER
            setTextColor(SecondaryInk)
            background = rounded(Control, dp(12))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                leftMargin = dp(4)
            }
            setOnClickListener { onClick() }
        }

    private fun actionChip(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(SecondaryInk)
            background = rounded(Control, dp(14))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                rightMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }

    private fun navAction(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(SecondaryInk)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(Color.TRANSPARENT, dp(16))
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            setOnClickListener { onClick() }
        }

    private fun contactRow(label: String, value: String, action: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(SoftInk)
                layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 15f
                setTextColor(Ink)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = action
                textSize = 12.5f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Accent)
                background = rounded(Color.rgb(232, 236, 231), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(34)).apply {
                    leftMargin = dp(10)
                }
                setOnClickListener { onClick() }
            })
        }

    private fun contactDivider(): View =
        View(this).apply {
            setBackgroundColor(Hairline)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(52)
            }
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun roundedStroke(color: Int, radius: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), strokeColor)
        }

    private fun kindBadge(kind: DocKind): TextView =
        TextView(this).apply {
            text = kind.shortLabel
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(kind.foreground)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(kind.background, dp(12))
        }

    private fun kindTextBadge(kind: DocKind): TextView =
        TextView(this).apply {
            text = kind.label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(kind.foreground)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(kind.background, dp(13))
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
        }

    private fun buildReaderSubtitle(doc: StoredDocument): String {
        val parts = mutableListOf(doc.kind.label, fileSize(doc.originalPath))
        if (doc.entryKind() == DocKind.HTML) {
            parts += if (isLocalScriptEnabled(doc)) "本地交互" else "安全预览"
        }
        if (doc.kind == DocKind.ZIP) parts += "入口 ${File(doc.entryPath).name}"
        return parts.joinToString(" · ")
    }

    private fun fileSize(path: String): String {
        val length = File(path).length().coerceAtLeast(0)
        return when {
            length >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", length / 1024f / 1024f)
            length >= 1024 -> String.format(Locale.US, "%.0f KB", length / 1024f)
            else -> "$length B"
        }
    }

    private fun isLocalScriptEnabled(doc: StoredDocument): Boolean =
        doc.entryKind() == DocKind.HTML && !getPreferences(Context.MODE_PRIVATE).getStringSet("disabled_html_interaction", emptySet()).orEmpty().contains(doc.id)

    private fun setLocalScriptEnabled(doc: StoredDocument, enabled: Boolean) {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val disabled = prefs.getStringSet("disabled_html_interaction", emptySet()).orEmpty().toMutableSet()
        if (enabled) disabled.remove(doc.id) else disabled.add(doc.id)
        prefs.edit().putStringSet("disabled_html_interaction", disabled).apply()
    }

    private fun parseFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null) return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
        return data.data?.let { arrayOf(it) }
    }

    private fun emptyWebResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private class LocalToolBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun saveDataUrl(fileName: String?, mimeType: String?, dataUrl: String?) {
            activity.saveDataUrlFromPage(fileName, mimeType, dataUrl)
        }
    }

    private fun saveDataUrlFromPage(fileName: String?, mimeType: String?, dataUrl: String?) {
        runCatching {
            val safeName = (fileName ?: "download").sanitizeDownloadName()
            val payload = dataUrl ?: throw IllegalArgumentException("下载数据为空")
            val comma = payload.indexOf(',')
            require(comma > 0) { "下载数据为空" }
            val bytes = Base64.decode(payload.substring(comma + 1), Base64.DEFAULT)
            val mime = mimeType?.ifBlank { null } ?: "application/octet-stream"
            val savedName = saveToDownloads(safeName, mime, bytes)
            runOnUiThread { Toast.makeText(this, "已保存：$savedName", Toast.LENGTH_LONG).show() }
        }.onFailure { error ->
            runOnUiThread { Toast.makeText(this, "保存失败：${error.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun saveToDownloads(fileName: String, mimeType: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Jiyue")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入下载文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return fileName
        }

        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    private fun String.sanitizeDownloadName(): String =
        replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val Surface = Color.rgb(244, 242, 238)
        private val Panel = Color.rgb(250, 249, 246)
        private val Hairline = Color.rgb(224, 221, 214)
        private val Ink = Color.rgb(22, 25, 29)
        private val SecondaryInk = Color.rgb(46, 51, 58)
        private val SoftInk = Color.rgb(132, 139, 149)
        private val Muted = Color.rgb(104, 111, 121)
        private val Control = Color.rgb(233, 232, 228)
        private val Accent = Color.rgb(35, 48, 60)
        private const val OpenDocumentRequest = 42
        private const val HtmlFileChooserRequest = 43
        private const val AuthorWebsite = "https://www.fengdaoai.com"
        private const val AuthorAccount = "冯导的AI工具箱"
        private val LocalSchemes = setOf("file", "content", "about", "data", "blob")
        private val LocalDownloadBridgeScript = """
            (function(){
              if (window.__jiyueDownloadBridgeInstalled) return;
              window.__jiyueDownloadBridgeInstalled = true;
              document.addEventListener('click', function(event) {
                var node = event.target;
                while (node && node.tagName !== 'A') node = node.parentElement;
                if (!node || !node.download || !node.href || node.href.indexOf('blob:') !== 0) return;
                event.preventDefault();
                event.stopPropagation();
                fetch(node.href).then(function(response) {
                  return response.blob();
                }).then(function(blob) {
                  var reader = new FileReader();
                  reader.onloadend = function() {
                    window.JiyueBridge.saveDataUrl(node.download || 'download', blob.type || 'application/octet-stream', reader.result);
                  };
                  reader.readAsDataURL(blob);
                }).catch(function(error) {
                  console.error('Jiyue blob save failed', error);
                });
              }, true);
            })();
        """.trimIndent()
    }
}

data class StoredDocument(
    val id: String,
    val name: String,
    val kind: DocKind,
    val originalPath: String,
    val entryPath: String,
    val updatedAt: Long,
    val favorite: Boolean
)

enum class DocKind(val label: String, val shortLabel: String, val background: Int, val foreground: Int) {
    MARKDOWN("Markdown", "MD", Color.rgb(229, 238, 232), Color.rgb(40, 93, 62)),
    TEXT("Text", "TXT", Color.rgb(226, 234, 240), Color.rgb(45, 82, 111)),
    HTML("HTML", "HTML", Color.rgb(238, 232, 223), Color.rgb(128, 76, 36)),
    ZIP("ZIP", "ZIP", Color.rgb(235, 229, 242), Color.rgb(84, 64, 127)),
}

private fun StoredDocument.entryKind(): DocKind =
    if (kind == DocKind.ZIP) DocumentStore.kindForPath(entryPath) else kind

data class Heading(val level: Int, val title: String, val id: String)

class DocumentStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("documents", Context.MODE_PRIVATE)
    private val baseDir = File(context.filesDir, "library").apply { mkdirs() }

    fun list(): List<StoredDocument> = load().sortedByDescending { it.updatedAt }

    fun toggleFavorite(id: String): StoredDocument {
        val docs = load().map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
        save(docs)
        return docs.first { it.id == id }
    }

    fun delete(id: String) {
        val doc = load().firstOrNull { it.id == id } ?: return
        File(doc.originalPath).parentFile?.deleteRecursively()
        save(load().filterNot { it.id == id })
    }

    fun importUri(uri: Uri): StoredDocument {
        val name = resolveName(uri).sanitizeName()
        val kind = kindForName(name)
        val temp = File(context.cacheDir, "incoming-${System.currentTimeMillis()}-$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取文件")

        val digest = sha256(temp)
        val existing = load().firstOrNull { it.id == digest }
        if (existing != null) {
            val refreshed = existing.copy(updatedAt = System.currentTimeMillis())
            save(load().map { if (it.id == digest) refreshed else it })
            temp.delete()
            return refreshed
        }

        val docDir = File(baseDir, digest).apply { mkdirs() }
        val original = File(docDir, name)
        temp.copyTo(original, overwrite = true)
        temp.delete()

        val entry = if (kind == DocKind.ZIP) extractZipEntry(original, File(docDir, "unzipped")) else original.absolutePath

        val doc = StoredDocument(digest, name, kind, original.absolutePath, entry, System.currentTimeMillis(), false)
        save(load().filterNot { it.id == digest } + doc)
        return doc
    }

    private fun kindForName(name: String): DocKind {
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith(".md") || lower.endsWith(".markdown") -> DocKind.MARKDOWN
            lower.endsWith(".txt") -> DocKind.TEXT
            lower.endsWith(".html") || lower.endsWith(".htm") -> DocKind.HTML
            lower.endsWith(".zip") -> DocKind.ZIP
            else -> throw IllegalArgumentException("仅支持 Markdown、HTML、TXT 和文档 ZIP 文件")
        }
    }

    private fun extractZipEntry(zipFile: File, outputDir: File): String {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val outputRoot = outputDir.canonicalFile
        var selected: File? = null
        var selectedRank = Int.MAX_VALUE

        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relativeName = entry.name.replace('\\', '/').trimStart('/')
                if (relativeName.isBlank()) {
                    zip.closeEntry()
                    continue
                }
                val target = File(outputRoot, relativeName).canonicalFile
                if (!target.path.startsWith(outputRoot.path + File.separator)) {
                    throw IllegalArgumentException("ZIP 内包含不安全路径")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output -> zip.copyTo(output) }
                    val rank = zipEntryRank(relativeName)
                    if (rank < selectedRank) {
                        selected = target
                        selectedRank = rank
                    }
                }
                zip.closeEntry()
            }
        }

        return selected?.absolutePath ?: throw IllegalArgumentException("ZIP 内未找到 Markdown、HTML 或 TXT 文件")
    }

    private fun zipEntryRank(name: String): Int {
        val lower = name.lowercase(Locale.US)
        val fileName = lower.substringAfterLast('/')
        return when {
            fileName == "index.html" || fileName == "index.htm" -> 0
            lower.endsWith(".html") || lower.endsWith(".htm") -> 1
            fileName == "readme.md" || fileName == "readme.markdown" -> 2
            lower.endsWith(".md") || lower.endsWith(".markdown") -> 3
            fileName == "readme.txt" -> 4
            lower.endsWith(".txt") -> 5
            else -> Int.MAX_VALUE
        }
    }

    companion object {
        fun kindForPath(path: String): DocKind {
            val lower = path.lowercase(Locale.US)
            return when {
                lower.endsWith(".md") || lower.endsWith(".markdown") -> DocKind.MARKDOWN
                lower.endsWith(".txt") -> DocKind.TEXT
                lower.endsWith(".html") || lower.endsWith(".htm") -> DocKind.HTML
                lower.endsWith(".zip") -> DocKind.ZIP
                else -> DocKind.TEXT
            }
        }
    }

    private fun resolveName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "document").name
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return "document-${System.currentTimeMillis()}.md"
    }

    private fun load(): List<StoredDocument> {
        val array = JSONArray(prefs.getString("items", "[]"))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val kind = runCatching { DocKind.valueOf(item.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            StoredDocument(
                item.getString("id"),
                item.getString("name"),
                kind,
                item.getString("originalPath"),
                item.getString("entryPath"),
                item.getLong("updatedAt"),
                item.optBoolean("favorite", false)
            )
        }
    }

    private fun save(docs: List<StoredDocument>) {
        val array = JSONArray()
        docs.forEach { doc ->
            array.put(JSONObject().apply {
                put("id", doc.id)
                put("name", doc.name)
                put("kind", doc.kind.name)
                put("originalPath", doc.originalPath)
                put("entryPath", doc.entryPath)
                put("updatedAt", doc.updatedAt)
                put("favorite", doc.favorite)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.sanitizeName(): String = replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.md" }
}

object Renderer {
    fun renderDocument(source: String, kind: DocKind, dark: Boolean): String {
        val body = if (kind == DocKind.MARKDOWN) renderMarkdown(source) else "<pre>${escape(source)}</pre>"
        val colors = if (dark) DarkColors else LightColors
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                body{margin:0;background:${colors.bg};color:${colors.text};font-family:-apple-system,BlinkMacSystemFont,"Noto Sans CJK SC",sans-serif;line-height:1.72;}
                main{max-width:780px;margin:0 auto;padding:24px 18px 56px;}
                h1,h2,h3{line-height:1.28;margin:1.4em 0 .65em;color:${colors.heading};}
                h1{font-size:1.9em} h2{font-size:1.5em} h3{font-size:1.22em}
                p{margin:.85em 0;} a{color:#2F6FED;} blockquote{border-left:4px solid ${colors.border};margin:1em 0;padding:.2em 1em;color:${colors.muted};background:${colors.panel};}
                code{background:${colors.panel};padding:.12em .34em;border-radius:5px;font-family:monospace;}
                pre{white-space:pre-wrap;background:${colors.panel};padding:14px;border-radius:8px;overflow:auto;}
                table{border-collapse:collapse;display:block;overflow-x:auto;white-space:nowrap;margin:1em 0;}
                th,td{border:1px solid ${colors.border};padding:8px 10px;} th{background:${colors.panel};}
                img{max-width:100%;height:auto;} hr{border:0;border-top:1px solid ${colors.border};margin:1.8em 0;}
              </style>
            </head>
            <body><main>$body</main></body>
            </html>
        """.trimIndent()
    }

    fun extractHeadings(source: String): List<Heading> =
        source.lineSequence().mapNotNull { line ->
            val match = Regex("^(#{1,6})\\s+(.+)$").find(line.trim()) ?: return@mapNotNull null
            val title = match.groupValues[2].trim()
            Heading(match.groupValues[1].length, title, slug(title))
        }.toList()

    private fun renderMarkdown(source: String): String {
        val out = StringBuilder()
        var inCode = false
        var inList = false
        val lines = source.lines()
        var i = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) i = end + 2
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (inCode) out.append("</code></pre>") else out.append("<pre><code>")
                inCode = !inCode
                i++
                continue
            }
            if (inCode) {
                out.append(escape(line)).append('\n')
                i++
                continue
            }
            if (trimmed.isBlank()) {
                if (inList) {
                    out.append("</ul>")
                    inList = false
                }
                i++
                continue
            }
            if (trimmed.contains('|') && i + 1 < lines.size && lines[i + 1].contains(Regex("\\|?\\s*:?-{3,}:?\\s*\\|"))) {
                val tableLines = mutableListOf(line)
                i += 2
                while (i < lines.size && lines[i].contains('|')) tableLines.add(lines[i++])
                out.append(renderTable(tableLines))
                continue
            }
            val heading = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
            if (heading != null) {
                if (inList) {
                    out.append("</ul>")
                    inList = false
                }
                val level = heading.groupValues[1].length
                val title = heading.groupValues[2].trim()
                out.append("<h$level id=\"${slug(title)}\">${inline(title)}</h$level>")
            } else if (trimmed.startsWith(">")) {
                out.append("<blockquote>${inline(trimmed.removePrefix(">").trim())}</blockquote>")
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || Regex("^\\d+\\.\\s+").containsMatchIn(trimmed)) {
                if (!inList) {
                    out.append("<ul>")
                    inList = true
                }
                val item = trimmed.replace(Regex("^([-*]|\\d+\\.)\\s+"), "")
                    .replace(Regex("^\\[ ]\\]\\s+"), "□ ")
                    .replace(Regex("^\\[[xX]\\]\\s+"), "☑ ")
                out.append("<li>${inline(item)}</li>")
            } else if (trimmed == "---") {
                out.append("<hr>")
            } else {
                if (inList) {
                    out.append("</ul>")
                    inList = false
                }
                out.append("<p>${inline(trimmed)}</p>")
            }
            i++
        }
        if (inList) out.append("</ul>")
        if (inCode) out.append("</code></pre>")
        return out.toString()
    }

    private fun renderTable(lines: List<String>): String {
        val rows = lines.map { it.trim().trim('|').split('|').map(String::trim) }
        val head = rows.firstOrNull().orEmpty()
        val body = rows.drop(1)
        return buildString {
            append("<table><thead><tr>")
            head.forEach { append("<th>${inline(it)}</th>") }
            append("</tr></thead><tbody>")
            body.forEach { row ->
                append("<tr>")
                row.forEach { append("<td>${inline(it)}</td>") }
                append("</tr>")
            }
            append("</tbody></table>")
        }
    }

    private fun inline(text: String): String {
        var html = escape(text)
        html = html.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        html = html.replace(Regex("!\\[([^]]*)]\\(([^)]+)\\)"), "<img alt=\"$1\" src=\"$2\">")
        html = html.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        html = html.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        return html
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun slug(text: String): String =
        "h-" + text.lowercase(Locale.US).replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-").trim('-')

    private data class Palette(val bg: String, val text: String, val heading: String, val muted: String, val panel: String, val border: String)
    private val LightColors = Palette("#ffffff", "#202226", "#111318", "#62666d", "#f3f4f6", "#d9dde3")
    private val DarkColors = Palette("#141618", "#e9eaec", "#ffffff", "#aeb3bb", "#22262a", "#3a4048")
}
