package com.dafu.estimator
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject
class Bridge(private val context:Context){
 @Volatile var seen:Long=Store.version
 @Volatile private var auctionData:String="{\"schemaVersion\":1}"
 fun stale()=seen!=Store.version
 @JavascriptInterface fun get(k:String):String?=if(k.startsWith("dafu-"))Store.get(k)else null
 @JavascriptInterface fun set(k:String,v:String){if(k.startsWith("dafu-")&&v.length<=1000000){Store.set(k,v);seen=Store.version}}
 @JavascriptInterface fun remove(k:String){if(k.startsWith("dafu-")){Store.remove(k);seen=Store.version}}
 @JavascriptInterface fun openAccessibilitySettings(){context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
 @JavascriptInterface fun readScreen():String=ScreenAssistService.snapshotJson()
 @JavascriptInterface fun clickScreenItem(index:Int):Boolean=ScreenAssistService.clickItem(index)
 @JavascriptInterface fun setOverlayEnabled(enabled:Boolean){val i=Intent(context,OverlayService::class.java);if(enabled){if(android.os.Build.VERSION.SDK_INT>=26)context.startForegroundService(i)else context.startService(i)}else context.stopService(i)}
 @JavascriptInterface fun publishAuctionData(json:String):Boolean{
  if(json.length>20000)return false
  return try{val obj=JSONObject(json);if(obj.optInt("schemaVersion")!=1)return false;auctionData=obj.toString();true}catch(e:Exception){false}
 }
 @JavascriptInterface fun getAuctionData():String=auctionData
}