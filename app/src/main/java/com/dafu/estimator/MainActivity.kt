package com.dafu.estimator
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
class MainActivity:Activity(){
 private lateinit var web:WebView
 private lateinit var bridge:Bridge
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);web=WebView(this);bridge=WebSetup.attach(web);setContentView(web,ViewGroup.LayoutParams(-1,-1));web.loadUrl(WebSetup.URL)}
 override fun onResume(){super.onResume();if(::bridge.isInitialized&&bridge.stale())web.reload()}
 override fun onDestroy(){if(::web.isInitialized){(web.parent as? ViewGroup)?.removeView(web);web.destroy()};super.onDestroy()}
}