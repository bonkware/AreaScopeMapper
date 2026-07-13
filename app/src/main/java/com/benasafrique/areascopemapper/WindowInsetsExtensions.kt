package com.benasafrique.areascopemapper

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

fun View.applySystemBarsPadding(
    applyTop: Boolean = true,
    applyBottom: Boolean = true
) {
    val initialPaddingTop = paddingTop
    val initialPaddingBottom = paddingBottom
    
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(
            top = if (applyTop) initialPaddingTop + systemBars.top else initialPaddingTop,
            bottom = if (applyBottom) initialPaddingBottom + systemBars.bottom else initialPaddingBottom
        )
        insets
    }
}

fun View.applySystemBarsMargin(
    applyTop: Boolean = false,
    applyBottom: Boolean = false
) {
    val initialTopMargin = (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    val initialBottomMargin = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (applyTop) topMargin = initialTopMargin + systemBars.top
            if (applyBottom) bottomMargin = initialBottomMargin + systemBars.bottom
        }
        insets
    }
}
