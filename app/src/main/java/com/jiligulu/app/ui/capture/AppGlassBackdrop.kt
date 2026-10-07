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
import com.jiligulu.app.data.prefs.GlobalGlassFrameRate
import com.jiligulu.app.data.prefs.GlobalGlassPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal data class AppGlassSample(val bitmap: Bitmap, val offsetX: Float, val offsetY: Float)

/** Only our resumed Activity window; never captures another app or starts screen sharing. */
internal object AppGlassBackdrop {
    // The small decorative lens does not need to copy an Activity at the page's
    // 60–120 Hz cadence. It remains event-driven and is completely idle at rest.
    private var minCopyIntervalMillis = GlobalGlassFrameRate.DEFAULT.intervalMillis
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preferenceJob: Job? = null
    private var source=WeakReference<Window>(null)
    private var owner=WeakReference<Window>(null)
    private val dialogs=ArrayList<WeakReference<Window>>()
    private val handler=Handler(Looper.getMainLooper())
    private val viewLocation=IntArray(2)
    private val windowLocation=IntArray(2)
    private var inFlight=false
    private var epoch=0L
    private var lastRequest=0L
    private var buffers=arrayOfNulls<Bitmap>(2)
    private var nextBuffer=0
    private var publishedBitmap:Bitmap?=null
    private var publishedRect:Rect?=null
    private var publishedOffsetX=0f
    private var publishedOffsetY=0f
    private var publishedViewWidth=0
    private var publishedViewHeight=0
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
    fun watch(view:GlassFloatingBubbleView) {
        watched=WeakReference(view)
        owner.get()?.let(::observePreference)
        switchWindow(source.get())
    }
    fun unwatch(view:GlassFloatingBubbleView) {
        if(watched.get()===view) {
            watched.clear();cancelRefresh()
            preferenceJob?.cancel();preferenceJob=null
            source.get()?.let { window -> drawListener?.let {
                if(window.decorView.viewTreeObserver.isAlive) window.decorView.viewTreeObserver.removeOnDrawListener(it)
            } }
            drawListener=null
        }
    }
    private fun cancelRefresh() {
        handler.removeCallbacks(refreshTask);handler.removeCallbacks(drawTask)
        refreshPending=false;queued=false
    }
    private fun forgetPublishedFrame() {publishedBitmap=null;publishedRect=null}
    /** Coalesce only requested work. There is no timer while the scene and bubble are idle. */
    private fun deferRefresh() {
        refreshPending=true
        handler.removeCallbacks(refreshTask)
        if(!inFlight) handler.postDelayed(refreshTask,(minCopyIntervalMillis-(SystemClock.uptimeMillis()-lastRequest)).coerceAtLeast(0))
    }
    private fun observePreference(window:Window) {
        if(preferenceJob?.isActive==true || watched.get()==null) return
        preferenceJob=scope.launch {
            GlobalGlassPrefs(window.context).frameRate.collect {rate ->
                minCopyIntervalMillis=rate.intervalMillis
                if(refreshPending) deferRefresh()
            }
        }
    }
    fun resume(window:Window) {owner=WeakReference(window);observePreference(window);switchWindow(dialogs.lastOrNull()?.get() ?: window)}
    fun dialog(window:Window,visible:Boolean) {
        dialogs.removeAll {it.get()==null||it.get()===window}
        if(visible) dialogs.add(WeakReference(window))
        if(owner.get()!=null) switchWindow(dialogs.lastOrNull()?.get() ?: owner.get())
    }
    private fun switchWindow(window:Window?) {
        epoch++
        cancelRefresh()
        forgetPublishedFrame()
        source.get()?.let {old ->drawListener?.let {if(old.decorView.viewTreeObserver.isAlive) old.decorView.viewTreeObserver.removeOnDrawListener(it)}}
        drawListener=null;source=WeakReference(window);watched.get()?.clearBackdrop()
        if(window!=null) GlobalGlassBackdrop.clearFrame()
        ScreenCaptureService.refreshGlassEnvironment()
        if(window==null || watched.get()==null) return
        val listener=ViewTreeObserver.OnDrawListener {
            if(!queued) {queued=true;handler.post(drawTask)}
        }
        drawListener=listener;window.decorView.viewTreeObserver.addOnDrawListener(listener)
        window.decorView.post {watched.get()?.refreshBackdrop()}
    }
    fun pause(window:Window) {if(owner.get()===window) {preferenceJob?.cancel();preferenceJob=null;owner.clear();switchWindow(null)}}
    fun available()=source.get()?.decorView?.isShown==true
    /** Reproject the last trustworthy crop at the current coordinates, never its old offset. */
    fun cachedFor(view:View):AppGlassSample? {
        val window=source.get()?.takeIf {it.decorView.isShown} ?: return null
        val bitmap=publishedBitmap ?: return null
        val rect=publishedRect ?: return null
        view.getLocationOnScreen(viewLocation);window.decorView.getLocationOnScreen(windowLocation)
        val x=(viewLocation[0]-windowLocation[0]-rect.left).toFloat()
        val y=(viewLocation[1]-windowLocation[1]-rect.top).toFloat()
        if(x<0 || y<0 || x+view.width>bitmap.width || y+view.height>bitmap.height) return null
        return AppGlassSample(bitmap,x,y)
    }
    fun copyBehind(view:View,callback:(Bitmap?,Float,Float)->Unit) {
        val window=source.get()
        if(window==null||!window.decorView.isShown) {forgetPublishedFrame();callback(null,0f,0f);return}
        if(view.width<=0 || !view.isAttachedToWindow || !view.isShown) return
        if(inFlight || SystemClock.uptimeMillis()-lastRequest<minCopyIntervalMillis) {deferRefresh();return}
        view.getLocationOnScreen(viewLocation);window.decorView.getLocationOnScreen(windowLocation)
        val x=viewLocation[0]-windowLocation[0];val y=viewLocation[1]-windowLocation[1]
        val pad=(view.width*.28f).roundToInt()
        val cropWidth=minOf(window.decorView.width,view.width+pad*2)
        val cropHeight=minOf(window.decorView.height,view.height+pad*2)
        if(cropWidth<=0 || cropHeight<=0) {forgetPublishedFrame();callback(null,0f,0f);return}
        val left=(x-pad).coerceIn(0,window.decorView.width-cropWidth)
        val top=(y-pad).coerceIn(0,window.decorView.height-cropHeight)
        val rect=Rect(left,top,left+cropWidth,top+cropHeight)
        if(rect.width()<=0||rect.height()<=0) {forgetPublishedFrame();callback(null,0f,0f);return}
        // A suppressed identical sample must not make the next request overwrite the
        // bitmap still held by the visible shader. Advance only after publishing.
        val index=nextBuffer
        val bitmap=buffers[index]?.takeIf {it.width==rect.width()&&it.height==rect.height()}
            ?: Bitmap.createBitmap(rect.width(),rect.height(),Bitmap.Config.ARGB_8888).also {buffers[index]=it}
        val offsetX=(x-rect.left).toFloat();val offsetY=(y-rect.top).toFloat()
        inFlight=true;lastRequest=SystemClock.uptimeMillis()
        val copyEpoch=epoch
        try { PixelCopy.request(window,rect,bitmap,{result ->
            scope.launch {
            if(result==PixelCopy.SUCCESS && copyEpoch==epoch && source.get()===window && view.isAttachedToWindow) {
                val previous=publishedBitmap
                val sameGeometry = publishedRect==rect && publishedOffsetX==offsetX && publishedOffsetY==offsetY &&
                    publishedViewWidth==view.width && publishedViewHeight==view.height && !view.isPressed &&
                    previous!=null && previous.width==bitmap.width && previous.height==bitmap.height
                val unchanged = sameGeometry && withContext(Dispatchers.Default) {
                    runCatching { previous!!.sameAs(bitmap) }.getOrDefault(false)
                }
                if(copyEpoch!=epoch || source.get()!==window || !view.isAttachedToWindow) {
                    inFlight=false;forgetPublishedFrame();callback(null,0f,0f)
                    if(refreshPending) deferRefresh()
                    return@launch
                }
                if(!unchanged) {
                    publishedBitmap=bitmap;publishedRect=rect
                    publishedOffsetX=offsetX;publishedOffsetY=offsetY
                    publishedViewWidth=view.width;publishedViewHeight=view.height
                    nextBuffer=1-index
                    val latest=cachedFor(view)
                    if(latest!=null) callback(latest.bitmap,latest.offsetX,latest.offsetY)
                    else {
                        callback(null,0f,0f)
                        view.getLocationOnScreen(viewLocation);window.decorView.getLocationOnScreen(windowLocation)
                        // A permanently out-of-window icon (e.g. beside a small dialog)
                        // must not turn a single failed reprojection into endless copies.
                        if(viewLocation[0]-windowLocation[0]!=x || viewLocation[1]-windowLocation[1]!=y) deferRefresh()
                    }
                }
                // No callback means no shader rebind or overlay invalidate when a page
                // redraw changed only pixels outside this small lens region.
            }
            else {forgetPublishedFrame();callback(null,0f,0f);if(source.get()!==window) handler.post {watched.get()?.refreshBackdrop()}}
            inFlight=false
            if(refreshPending) deferRefresh()
            }
        },handler) } catch (_:Exception) {
            inFlight=false;forgetPublishedFrame();callback(null,0f,0f)
            if(refreshPending) deferRefresh()
        }
    }
}
