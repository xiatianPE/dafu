package com.dafu.estimator

import android.content.Context

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
