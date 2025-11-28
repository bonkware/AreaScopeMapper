package com.benasafrique.areascopemapper

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

fun View.applySystemBarsPadding(
    applyTop: Boolean = true,
    applyBottom: Boolean = true
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(
            top = if (applyTop) systemBars.top else view.paddingTop,
            bottom = if (applyBottom) systemBars.bottom else view.paddingBottom
        )
        insets
    }
}
