package com.dafu.estimator
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
class ScreenAssistService:AccessibilityService(){
 override fun onServiceConnected(){serviceInfo=serviceInfo.apply{eventTypes=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC;flags=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;notificationTimeout=250};active=this}
 override fun onAccessibilityEvent(event:AccessibilityEvent?){active=this}
 override fun onInterrupt(){}
 override fun onDestroy(){if(active===this)active=null;super.onDestroy()}
 companion object{
 @Volatile private var active:ScreenAssistService?=null
 @Volatile private var latest=JSONArray()
 private val lock=Any()
 fun isConnected():Boolean=active!=null
  fun isEnabled(context:Context):Boolean{
   val raw=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?:return false
   val expected=ComponentName(context,ScreenAssistService::class.java).flattenToString()
   return raw.split(':').any{it.equals(expected,true)}
  }
  fun snapshotJson():String{
  val svc=active?:return JSONObject().put("enabled",false).put("items",JSONArray()).toString()
  val out=JSONArray();val root=svc.rootInActiveWindow
  if(root!=null){fun walk(n:AccessibilityNodeInfo,path:JSONArray,depth:Int){
   if(depth>18||out.length()>=180)return
   val label=listOf(n.text?.toString(),n.contentDescription?.toString()).filterNot{it.isNullOrBlank()}.distinct().joinToString(" · ")
   if(label.isNotBlank()||n.isClickable)out.put(JSONObject().put("text",label.take(180)).put("clickable",n.isClickable).put("path",path))
   for(i in 0 until n.childCount){val c=n.getChild(i)?:continue;walk(c,JSONArray(path.toString()).put(i),depth+1)}
  };walk(root,JSONArray(),0)}
  synchronized(lock){latest=out};return JSONObject().put("enabled",true).put("items",out).toString()
 }
 fun clickItem(index:Int):Boolean{
  val svc=active?:return false;val path=synchronized(lock){latest.optJSONObject(index)?.optJSONArray("path")}?:return false
  var node=svc.rootInActiveWindow?:return false
  for(i in 0 until path.length())node=node.getChild(path.optInt(i))?:return false
  var target:AccessibilityNodeInfo?=node;while(target!=null&&!target.isClickable)target=target.parent
  return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
 }
 }
}