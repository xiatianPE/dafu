package com.dafu.estimator

import android.app.Application

class DafuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
