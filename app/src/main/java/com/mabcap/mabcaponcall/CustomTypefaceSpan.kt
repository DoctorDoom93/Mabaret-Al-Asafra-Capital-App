package com.mabcap.mabcaponcall

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {

    override fun updateDrawState(ds: TextPaint) {
        applyCustomTypeFace(ds, typeface)
    }

    override fun updateMeasureState(paint: TextPaint) {
        applyCustomTypeFace(paint, typeface)
    }

    private fun applyCustomTypeFace(paint: TextPaint, tf: Typeface) {
        paint.isFakeBoldText = true // تفعيل الجرأة والسماكة التلقائية
        paint.typeface = tf
    }
}