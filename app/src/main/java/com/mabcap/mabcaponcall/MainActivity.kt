package com.mabcap.mabcaponcall

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var btnRefresh: ImageButton
    private lateinit var refreshProgressBar: ProgressBar

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

        // 1. تحميل ملفات الخطوط من مجلد res/font
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

        // تفعيل عملية التحديث عند الضغط على الزر مع استخدام حركة Fade
        btnRefresh.setOnClickListener {
            // 💡 التحقق من وجود إنترنت أولاً
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

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
        }

        // 🛠️ تحديث الـ WebViewClient للتعامل مع روابط tel: والروابط الخارجية
        webView.webViewClient = object : WebViewClient() {

            // للأجهزة الحديثة (Android 7.0 وما فوق)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleCustomMarketSchemes(url)
            }

            // للأجهزة القديمة
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleCustomMarketSchemes(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingProgressBar.visibility = View.GONE

                // إخفاء مؤشر التحميل وإعادة إظهار زر التحديث تدريجياً بـ Fade
                showProgressBar(false)

                ObjectAnimator.ofFloat(webView, "alpha", 0f, 1f).apply {
                    duration = 400
                    start()
                }
            }
        }

        // بدء التحميل لأول مرة مع تطبيق حركة Fade على الزر والمؤشر
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

    // 💡 دالة فحص ومعالجة الروابط الخاصة (مثل الاتصال والواتساب)
    private fun handleCustomMarketSchemes(url: String): Boolean {
        if (url.startsWith("tel:")) {
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse(url))
                startActivity(intent)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        else if (url.startsWith("whatsapp:") || url.contains("wa.me")) {
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
                // محاولة التحميل مجدداً عبر زر التحديث نفسه
                btnRefresh.performClick()
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
            }

        val dialog = builder.create()
        dialog.show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    // دالة التحويل المتدرج (Fade In/Out) بين زر التحديث ومؤشر التحميل الدائري
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
}