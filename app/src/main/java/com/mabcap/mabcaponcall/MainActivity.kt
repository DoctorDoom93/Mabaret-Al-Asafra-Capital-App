package com.mabcap.mabcaponcall

import android.animation.ObjectAnimator
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import org.json.JSONArray
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

        // فحص التحديثات تلقائياً في الخلفية عند فتح التطبيق
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

        // تفاعل زر التحديث للتحقق والتحميل
        btnUpdate.setOnClickListener {
            if (!isConnectedToInternet()) {
                showNoInternetDialogUpdateBTN()
                return@setOnClickListener
            }

            showUpdateProgressBar(true)

            updateInfo?.let { info ->
                downloadAndInstallApk(info.apkDownloadUrl)
            } ?: run {
                // إذا لم يتم جلب البيانات مسبقاً، قم بإعادة الفحص الآن
                CoroutineScope(Dispatchers.Main).launch {
                    val checker = GitHubUpdateChecker(this@MainActivity)
                    val info = checker.checkUpdate()
                    updateInfo = info

                    if (info != null) {
                        downloadAndInstallApk(info.apkDownloadUrl)
                    } else {
                        showUpdateProgressBar(false)
                        Toast.makeText(this@MainActivity, "عذراً، لا يوجد تحديث متاح حالياً", Toast.LENGTH_SHORT).show()
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

    // فحص التحديثات (مع خيار إظهار نافذة منبثقة تلقائية أو مجرد تخزينها للزر)
    private fun checkForAppUpdates(showDialogOnAvailable: Boolean) {
        if (!isConnectedToInternet()) return

        CoroutineScope(Dispatchers.Main).launch {
            val checker = GitHubUpdateChecker(this@MainActivity)
            updateInfo = checker.checkUpdate()

            if (updateInfo != null && showDialogOnAvailable) {
                showUpdateDialog(this@MainActivity, updateInfo!!) {
                    updateInfo?.let { downloadAndInstallApk(it.apkDownloadUrl) }
                }
            }
        }
    }

    // دالة تحميل وتثبيت الـ APK عبر DownloadManager
// دالة تحميل وتثبيت الـ APK مباشرة عبر Coroutines (بدون BroadcastReceiver)
    private fun downloadAndInstallApk(apkUrl: String) {
        showUpdateProgressBar(true)
        Toast.makeText(this, "جاري تحميل التحديث...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(apkUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    withContext(Dispatchers.Main) {
                        showUpdateProgressBar(false)
                        Toast.makeText(this@MainActivity, "فشل الاتصال لتحميل التحديث", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // تحديد مكان حفظ الملف
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
                if (file.exists()) {
                    file.delete()
                }

                // كتابة الملف من الإنترنت إلى الهاتف
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // العودة للـ Main Thread لتثبيت التطبيق وإخفاء الـ Progress
                withContext(Dispatchers.Main) {
                    showUpdateProgressBar(false)
                    installApk(file)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
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

// كلاس مساعد لجلب بيانات الإصدار من GitHub Releases API
class GitHubUpdateChecker(private val context: Context) {
    // استبدل DoctorDoom93 واسم المستودع بما يناسب مشروعك الفعلي على GitHub
    private val owner = "DoctorDoom93"
    private val repo = "mabcaponcall"
    private val apiReleaseUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

    suspend fun checkUpdate(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(apiReleaseUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Android-App")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val tagName = json.getString("tag_name").replace("v", "")
                    val releaseNotes = json.optString("body", "لا توجد تفاصيل متاحة.")

                    val assets: JSONArray = json.getJSONArray("assets")
                    var apkUrl: String? = null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    if (apkUrl == null) return@withContext null

                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }

                    val latestVersionCode = tagName.toIntOrNull() ?: 1

                    if (latestVersionCode > currentVersionCode) {
                        return@withContext UpdateInfo(
                            versionName = tagName,
                            versionCode = latestVersionCode,
                            releaseNotes = releaseNotes,
                            apkDownloadUrl = apkUrl
                        )
                    }
                }
            }
            catch (e: Exception) {
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
        .setCancelable(false)

    val dialog = builder.create()
    dialog.show()
    dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
}