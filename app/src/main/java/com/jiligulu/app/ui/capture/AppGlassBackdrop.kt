package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Only our resumed Activity window; never captures another app or starts screen sharing. */
internal object AppGlassBackdrop {
    private var source=WeakReference<Window>(null)
    private var owner=WeakReference<Window>(null)
    private val dialogs=ArrayList<WeakReference<Window>>()
    private val handler=Handler(Looper.getMainLooper())
    private val viewLocation=IntArray(2)
    private val windowLocation=IntArray(2)
    private var inFlight=false
    private var lastRequest=0L
    private var buffers=arrayOfNulls<Bitmap>(2)
    private var nextBuffer=0
    private var watched=WeakReference<GlassFloatingBubbleView>(null)
    private var drawListener:ViewTreeObserver.OnDrawListener?=null
    private var queued=false
    private var refreshPending=false
    private val refreshTask=Runnable {
        refreshPending=false
        watched.get()?.takeIf {it.isAttachedToWindow && it.isShown}?.refreshBackdrop()
    }
    private val drawTask=Runnable {
        queued=false
        watched.get()?.takeIf {it.isAttachedToWindow && it.isShown}?.refreshBackdrop()
    }
    fun watch(view:GlassFloatingBubbleView) {watched=WeakReference(view)}
    fun unwatch(view:GlassFloatingBubbleView) {
        if(watched.get()===view) {
            watched.clear();cancelRefresh()
        }
    }
    private fun cancelRefresh() {
        handler.removeCallbacks(refreshTask);handler.removeCallbacks(drawTask)
        refreshPending=false;queued=false
    }
    /** Coalesce only requested work. There is no timer while the scene and bubble are idle. */
    private fun deferRefresh() {
        refreshPending=true
        handler.removeCallbacks(refreshTask)
        if(!inFlight) handler.postDelayed(refreshTask,(24-(SystemClock.uptimeMillis()-lastRequest)).coerceAtLeast(0))
    }
    fun resume(window:Window) {owner=WeakReference(window);switchWindow(dialogs.lastOrNull()?.get() ?: window)}
    fun dialog(window:Window,visible:Boolean) {
        dialogs.removeAll {it.get()==null||it.get()===window}
        if(visible) dialogs.add(WeakReference(window))
        if(owner.get()!=null) switchWindow(dialogs.lastOrNull()?.get() ?: owner.get())
    }
    private fun switchWindow(window:Window?) {
        cancelRefresh()
        source.get()?.let {old ->drawListener?.let {if(old.decorView.viewTreeObserver.isAlive) old.decorView.viewTreeObserver.removeOnDrawListener(it)}}
        drawListener=null;source=WeakReference(window);watched.get()?.clearBackdrop()
        if(window==null) return
        val listener=ViewTreeObserver.OnDrawListener {
            if(!queued) {queued=true;handler.post(drawTask)}
        }
        drawListener=listener;window.decorView.viewTreeObserver.addOnDrawListener(listener)
        window.decorView.post {watched.get()?.refreshBackdrop()}
    }
    fun pause(window:Window) {if(owner.get()===window) {owner.clear();switchWindow(null)}}
    fun available()=source.get()?.decorView?.isShown==true
    fun copyBehind(view:View,callback:(Bitmap?,Float,Float)->Unit) {
        val window=source.get()
        if(window==null||!window.decorView.isShown) {callback(null,0f,0f);return}
        if(view.width<=0 || !view.isAttachedToWindow || !view.isShown) return
        if(inFlight || SystemClock.uptimeMillis()-lastRequest<24) {deferRefresh();return}
        view.getLocationOnScreen(viewLocation);window.decorView.getLocationOnScreen(windowLocation)
        val x=viewLocation[0]-windowLocation[0];val y=viewLocation[1]-windowLocation[1]
        val pad=(view.width*.28f).roundToInt()
        val rect=Rect((x-pad).coerceAtLeast(0),(y-pad).coerceAtLeast(0),
            (x+view.width+pad).coerceAtMost(window.decorView.width),(y+view.height+pad).coerceAtMost(window.decorView.height))
        if(rect.width()<=0||rect.height()<=0) {callback(null,0f,0f);return}
        val index=nextBuffer;nextBuffer=1-nextBuffer
        val bitmap=buffers[index]?.takeIf {it.width==rect.width()&&it.height==rect.height()}
            ?: Bitmap.createBitmap(rect.width(),rect.height(),Bitmap.Config.ARGB_8888).also {buffers[index]=it}
        val offsetX=(x-rect.left).toFloat();val offsetY=(y-rect.top).toFloat()
        inFlight=true;lastRequest=SystemClock.uptimeMillis()
        try { PixelCopy.request(window,rect,bitmap,{result ->
            inFlight=false
            if(result==PixelCopy.SUCCESS && source.get()===window && view.isAttachedToWindow) callback(bitmap,offsetX,offsetY)
            else {callback(null,0f,0f);if(source.get()!==window) handler.post {watched.get()?.refreshBackdrop()}}
            if(refreshPending) deferRefresh()
        },handler) } catch (_:Exception) {
            inFlight=false;callback(null,0f,0f)
            if(refreshPending) deferRefresh()
        }
    }
}
