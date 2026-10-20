package com.mabcap.mabcaponcall

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// نموذج بيانات التحديث
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val apkDownloadUrl: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnUpdate: ImageButton
    private lateinit var refreshProgressBar: ProgressBar
    private lateinit var updateProgressBar: ProgressBar

    // متغير لحفظ بيانات التحديث عند فحصها
    private var updateInfo: UpdateInfo? = null

    private val URL = "https://doctordoom93.github.io/oncallphone.html"

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale("ar")
        Locale.setDefault(locale)

        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = ContextCompat.getColor(this, R.color.slate)

        setContentView(R.layout.activity_main)

        // ============================================================
        // 💡 إعداد عنوان الـ Toolbar بخطين مختلفين (عربي وإنجليزي)
        // ============================================================
        val toolbarTitle = findViewById<TextView>(R.id.toolbarTitle)

        val arabicFont = ResourcesCompat.getFont(this, R.font.noto_sans_arabic_bold)
        val englishFont = ResourcesCompat.getFont(this, R.font.google_sans_bold)

        val titleText = "مستشفى مبرة العصافرة Capital"
        val spannableBuilder = SpannableStringBuilder(titleText)

        arabicFont?.let {
            spannableBuilder.setSpan(CustomTypefaceSpan(it), 0, 20, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        englishFont?.let {
            spannableBuilder.setSpan(CustomTypefaceSpan(it), 21, titleText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        toolbarTitle.text = spannableBuilder

        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())

            view.setPadding(
                view.paddingLeft,
                statusBarInsets.top,
                view.paddingRight,
                view.paddingBottom
            )

            val typedValue = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
                val actionBarHeight = android.util.TypedValue.complexToDimensionPixelSize(
                    typedValue.data, resources.displayMetrics
                )
                view.layoutParams.height = actionBarHeight + statusBarInsets.top
            }

            insets
        }

        webView = findViewById(R.id.webView)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        btnRefresh = findViewById(R.id.btnRefresh)
        refreshProgressBar = findViewById(R.id.refreshProgressBar)
        btnUpdate = findViewById(R.id.btnUpdate)
        updateProgressBar = findViewById(R.id.updateProgressBar)

        // فحص التحديثات تلقائياً في الخلفية عند فتح التطبيق مع إظهار Toast بالنتيجة
        checkForAppUpdates(false)

        // تفعيل عملية التحديث عند الضغط على زر الـ Refresh
        btnRefresh.setOnClickListener {
            if (!isConnectedToInternet()) {
                showNoInternetDialogBTN()
                return@setOnClickListener
            }

            showProgressBar(true)
            loadingProgressBar.visibility = View.VISIBLE
            webView.alpha = 0f
            webView.reload()
        }

        if (!isConnectedToInternet()) {
            showNoInternetDialog()
            return
        }

        // تفاعل زر التحديث للتحقق وإظهار الـ Dialog بالتفاصيل
        btnUpdate.setOnClickListener {
            if (!isConnectedToInternet()) {
                showNoInternetDialogUpdateBTN()
                return@setOnClickListener
            }

            updateInfo?.let { info ->
                // إظهار نافذة تفاصيل التحديث مباشرة إذا كانت البيانات مخزنة مسبقاً
                showUpdateDialog(this, info) {
                    // التحقق من الإنترنت مرة أخرى قبل بدء التحميل من داخل الـ Dialog
                    if (!isConnectedToInternet()) {
                        showNoInternetDialogUpdateBTN()
                        return@showUpdateDialog
                    }
                    downloadAndInstallApk(info.apkDownloadUrl)
                }
            } ?: run {
                // إذا لم يتم جلب البيانات مسبقاً، قم بإعادة الفحص الآن
                showUpdateProgressBar(true)
                CoroutineScope(Dispatchers.Main).launch {
                    val checker = JsonUpdateChecker(this@MainActivity)
                    val info = checker.checkUpdate()
                    updateInfo = info
                    showUpdateProgressBar(false)

                    if (info != null) {
                        showUpdateDialog(this@MainActivity, info) {
                            if (!isConnectedToInternet()) {
                                showNoInternetDialogUpdateBTN()
                                return@showUpdateDialog
                            }
                            downloadAndInstallApk(info.apkDownloadUrl)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "آخر إصدار مثبت بالفعل", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        if (!isConnectedToInternet()) {
            showNoInternetDialog()
            return
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleCustomMarketSchemes(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleCustomMarketSchemes(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingProgressBar.visibility = View.GONE
                showProgressBar(false)

                ObjectAnimator.ofFloat(webView, "alpha", 0f, 1f).apply {
                    duration = 400
                    start()
                }
            }
        }

        loadingProgressBar.visibility = View.VISIBLE
        showProgressBar(true)
        webView.alpha = 0f
        webView.loadUrl(URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val builder = MaterialAlertDialogBuilder(this@MainActivity, R.style.CustomAlertDialogTheme)
                    .setTitle("تأكيد الخروج")
                    .setMessage("هل تريد بالتأكيد إغلاق التطبيق؟")
                    .setCancelable(false)
                    .setPositiveButton("موافق") { _, _ ->
                        finishAffinity()
                    }
                    .setNegativeButton("إلغاء") { dialog, _ ->
                        dialog.dismiss()
                    }

                val dialog = builder.create()
                dialog.show()
                dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
        })
    }

    // فحص التحديثات عند فتح التطبيق وإظهار Toast بالنتيجة
    private fun checkForAppUpdates(showToastResult: Boolean) {
        if (!isConnectedToInternet()) return

        CoroutineScope(Dispatchers.Main).launch {
            val checker = JsonUpdateChecker(this@MainActivity)
            updateInfo = checker.checkUpdate()

            if (showToastResult) {
                if (updateInfo != null) {
                    Toast.makeText(this@MainActivity, "يتوفر تحديث جديد للإصدار: ${updateInfo?.versionName}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "آخر إصدار مثبت بالفعل", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // دالة تحميل وتثبيت الـ APK مباشرة عبر Coroutines
    private fun downloadAndInstallApk(apkUrl: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)

        // 💡 استخدام LinearProgressIndicator الخاص بـ Material 3
        val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.dialogProgressBar)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvProgressPercent)

        val dialog = MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle("جاري تحميل التحديث...")
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dialog.show()

        showUpdateProgressBar(true)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(apkUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        showUpdateProgressBar(false)
                        Toast.makeText(this@MainActivity, "فشل الاتصال لتحميل التحديث", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val fileLength = connection.contentLength
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
                if (file.exists()) {
                    file.delete()
                }

                val inputStream = connection.inputStream
                val outputStream = file.outputStream()

                val data = ByteArray(8192)
                var totalBytesRead: Long = 0
                var count: Int

                while (inputStream.read(data).also { count = it } != -1) {
                    totalBytesRead += count.toLong()
                    outputStream.write(data, 0, count)

                    if (fileLength > 0) {
                        val progress = ((totalBytesRead * 100) / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            // 💡 تحديث التقدم مع تأثير الحركة السلس (Smooth Animation) الخاص بـ M3
                            progressBar.setProgressCompat(progress, true)
                            tvPercent.text = "$progress%"
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    showUpdateProgressBar(false)
                    installApk(file)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    showUpdateProgressBar(false)
                    Toast.makeText(this@MainActivity, "حدث خطأ أثناء التحميل: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun installApk(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun handleCustomMarketSchemes(url: String): Boolean {
        if (url.startsWith("tel:")) {
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse(url))
                startActivity(intent)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (url.startsWith("whatsapp:") || url.contains("wa.me")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                return true
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun isConnectedToInternet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        return false
    }

    private fun showNoInternetDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle("خطأ في الاتصال")
            .setMessage("لا يوجد اتصال بالإنترنت. يرجى التحقق من اتصالك وإعادة المحاولة.")
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة") { _, _ -> recreate() }
            .setNegativeButton("خروج") { _, _ -> finish() }

        val dialog = builder.create()
        dialog.show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun showNoInternetDialogBTN() {
        val builder = MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle("خطأ في الاتصال")
            .setMessage("لا يوجد اتصال بالإنترنت. يرجى التحقق من اتصالك وإعادة المحاولة.")
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة") { dialog, _ ->
                dialog.dismiss()
                btnRefresh.performClick()
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
            }

        val dialog = builder.create()
        dialog.show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun showNoInternetDialogUpdateBTN() {
        val builder = MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle("خطأ في الاتصال")
            .setMessage("لا يوجد اتصال بالإنترنت. يرجى التحقق من اتصالك وإعادة المحاولة.")
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة") { dialog, _ ->
                dialog.dismiss()
                btnUpdate.performClick()
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
            }

        val dialog = builder.create()
        dialog.show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun showProgressBar(show: Boolean) {
        if (show) {
            btnRefresh.animate().alpha(0f).setDuration(200).withEndAction {
                btnRefresh.visibility = View.GONE
            }.start()

            refreshProgressBar.visibility = View.VISIBLE
            refreshProgressBar.alpha = 0f
            refreshProgressBar.animate().alpha(1f).setDuration(200).start()
        } else {
            refreshProgressBar.animate().alpha(0f).setDuration(200).withEndAction {
                refreshProgressBar.visibility = View.GONE
            }.start()

            btnRefresh.visibility = View.VISIBLE
            btnRefresh.alpha = 0f
            btnRefresh.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun showUpdateProgressBar(show: Boolean) {
        if (show) {
            btnUpdate.animate().alpha(0f).setDuration(200).withEndAction {
                btnUpdate.visibility = View.GONE
            }.start()

            updateProgressBar.visibility = View.VISIBLE
            updateProgressBar.alpha = 0f
            updateProgressBar.animate().alpha(1f).setDuration(200).start()
        } else {
            updateProgressBar.animate().alpha(0f).setDuration(200).withEndAction {
                updateProgressBar.visibility = View.GONE
            }.start()

            btnUpdate.visibility = View.VISIBLE
            btnUpdate.alpha = 0f
            btnUpdate.animate().alpha(1f).setDuration(200).start()
        }
    }
}

// ============================================================
// 💡 كلاس فحص التحديثات باستخدام ملف JSON
// ============================================================
class JsonUpdateChecker(private val context: Context) {

    private val jsonUrl = "https://doctordoom93.github.io/update.json"

    suspend fun checkUpdate(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(jsonUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.useCaches = false

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val latestVersionCode = json.getInt("versionCode")
                    val latestVersionName = json.optString("versionName", "")
                    val releaseNotes = json.optString("releaseNotes", "لا توجد تفاصيل متاحة.")
                    val apkUrl = json.getString("apkUrl")

                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }

                    if (latestVersionCode > currentVersionCode) {
                        return@withContext UpdateInfo(
                            versionName = latestVersionName,
                            versionCode = latestVersionCode,
                            releaseNotes = releaseNotes,
                            apkDownloadUrl = apkUrl
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }
}

// نافذة حوار عرض التحديث بالتصميم المتوافق مع التطبيق
fun showUpdateDialog(context: Context, updateInfo: UpdateInfo, onConfirmDownload: () -> Unit) {
    val builder = MaterialAlertDialogBuilder(context, R.style.CustomAlertDialogTheme)
        .setTitle("يتوفر تحديث جديد (${updateInfo.versionName})")
        .setMessage("ما الجديد:\n\n${updateInfo.releaseNotes}\n\nهل تريد تحديث التطبيق الآن؟")
        .setPositiveButton("تحديث الآن") { _, _ ->
            onConfirmDownload()
        }
        .setNegativeButton("لاحقاً", null)
        .setCancelable(true)

    val dialog = builder.create()
    dialog.show()
    dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
}