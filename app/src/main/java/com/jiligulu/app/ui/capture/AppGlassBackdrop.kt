package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import com.jiligulu.app.data.prefs.AppGlassFrameRate
import com.jiligulu.app.data.prefs.AppGlassPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal data class AppGlassSample(val bitmap: Bitmap, val offsetX: Float, val offsetY: Float)

/** Only our resumed Activity window; never captures another app or starts screen sharing. */
internal object AppGlassBackdrop {
    // The small decorative lens does not need to copy an Activity at the page's
    // 60–120 Hz cadence. It remains event-driven and is completely idle at rest.
    private var minCopyIntervalMillis = AppGlassFrameRate.DEFAULT.intervalMillis
    private var targetFps = AppGlassFrameRate.DEFAULT.fps
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preferenceJob: Job? = null
    private var source=WeakReference<Window>(null)
    private var owner=WeakReference<Window>(null)
    private val dialogs=ArrayList<WeakReference<Window>>()
    private val handler=Handler(Looper.getMainLooper())
    private val viewLocation=IntArray(2)
    private val windowLocation=IntArray(2)
    private var inFlight=false
    private var copyFailures=0
    private var epoch=0L
    private var drawnFrame=0L
    private var committedFrame=0L
    private var lastCommitAt=0L
    private var motionUntil=0L
    private var publishedTicket:OwnGlassFrameTicket?=null
    private var publishedSourceChangedAt:Long?=null
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
    private var expiryTask:Runnable?=null
    private var expiryTicket:OwnGlassFrameTicket?=null
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
            forgetPublishedFrame()
            // Drop strong pixel references. Do not recycle a bitmap still submitted to RenderThread.
            buffers=arrayOfNulls(2);nextBuffer=0
        }
    }
    private fun cancelRefresh() {
        handler.removeCallbacks(refreshTask);handler.removeCallbacks(drawTask)
        refreshPending=false;queued=false
        expiryTask?.let(handler::removeCallbacks);expiryTask=null;expiryTicket=null
    }
    private fun forgetPublishedFrame() {
        publishedBitmap=null;publishedRect=null;publishedTicket=null;publishedSourceChangedAt=null
        expiryTask?.let(handler::removeCallbacks);expiryTask=null;expiryTicket=null
    }
    /** One deadline only after source content changes; a still scene never starts a timer. */
    private fun armExpiry() {
        val ticket=publishedTicket ?: return
        if(ticket.committedFrame==committedFrame) {
            expiryTask?.let(handler::removeCallbacks);expiryTask=null;expiryTicket=null
            return
        }
        if(expiryTicket===ticket) return
        expiryTask?.let(handler::removeCallbacks)
        expiryTicket=ticket
        val task=Runnable {
            expiryTask=null;expiryTicket=null
            if(publishedTicket===ticket) {
                if(!publishedIsFresh()) {forgetPublishedFrame();watched.get()?.clearOwnBackdrop()}
                else if(publishedSourceChangedAt!=null) armExpiry()
            }
        }
        expiryTask=task
        val deadline=(publishedSourceChangedAt ?: ticket.completedAtMillis)+OwnGlassFramePolicy.holdMillis(targetFps)+1
        handler.postDelayed(task,(deadline-SystemClock.uptimeMillis()).coerceAtLeast(0))
    }
    /** Coalesce only requested work. There is no timer while the scene and bubble are idle. */
    private fun deferRefresh() {
        refreshPending=true
        handler.removeCallbacks(refreshTask)
        if(!inFlight) handler.postDelayed(refreshTask,(minCopyIntervalMillis-(SystemClock.uptimeMillis()-lastRequest)).coerceAtLeast(0))
    }
    private fun observePreference(window:Window) {
        if(preferenceJob?.isActive==true || watched.get()==null) return
        preferenceJob=scope.launch {
            AppGlassPrefs(window.context).frameRate.collect {rate ->
                minCopyIntervalMillis=rate.intervalMillis
                targetFps=rate.fps
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
        drawnFrame=0L;committedFrame=0L;lastCommitAt=0L;motionUntil=0L;copyFailures=0
        cancelRefresh()
        forgetPublishedFrame()
        source.get()?.let {old ->drawListener?.let {if(old.decorView.viewTreeObserver.isAlive) old.decorView.viewTreeObserver.removeOnDrawListener(it)}}
        drawListener=null;source=WeakReference(window);watched.get()?.releaseOwnBackdrop()
        if (window == null) {buffers=arrayOfNulls(2);nextBuffer=0}
        if(window==null || watched.get()==null) return
        val listener=ViewTreeObserver.OnDrawListener {
            if(watched.get()?.canSampleOwnBackdrop!=true)return@OnDrawListener
            val frame=++drawnFrame
            val frameEpoch=epoch
            val commit=Runnable {
                if(frameEpoch!=epoch||source.get()!==window)return@Runnable
                val now=SystemClock.uptimeMillis()
                if(lastCommitAt>0L&&now-lastCommitAt<50L)motionUntil=now+80L
                lastCommitAt=now;committedFrame=maxOf(committedFrame,frame)
                // A quiet texture can be hours old. Its first changed frame starts the
                // grace interval now, instead of briefly clearing it at the start of a swipe.
                if(publishedTicket?.committedFrame?.let { committedFrame>it }==true && publishedSourceChangedAt==null)
                    publishedSourceChangedAt=now
                if(!publishedIsFresh())watched.get()?.clearOwnBackdrop()
                armExpiry()
                if(!queued){queued=true;handler.post(drawTask)}
            }
            // Android recommends the commit callback with PixelCopy: OnDraw itself has
            // not yet submitted this frame. Older/software windows have only post-draw.
            if(Build.VERSION.SDK_INT>=29&&window.decorView.isHardwareAccelerated) {
                runCatching {window.decorView.viewTreeObserver.registerFrameCommitCallback {
                    if(Looper.myLooper()===handler.looper)commit.run()else handler.post(commit)
                }}.onFailure {handler.post(commit)}
            } else handler.post(commit)
        }
        drawListener=listener;window.decorView.viewTreeObserver.addOnDrawListener(listener)
        window.decorView.post {watched.get()?.refreshBackdrop()}
    }
    fun pause(window:Window) {if(owner.get()===window) {preferenceJob?.cancel();preferenceJob=null;owner.clear();switchWindow(null)}}
    fun available()=source.get()?.decorView?.isShown==true
    private fun publishedIsFresh():Boolean =publishedTicket?.let {
        OwnGlassFramePolicy.canDisplay(it,epoch,committedFrame,SystemClock.uptimeMillis(),targetFps,publishedSourceChangedAt)
    }==true
    fun matchesCurrentContent(bitmap:Bitmap?)=bitmap!=null&&publishedBitmap===bitmap&&publishedIsFresh()
    /** Reproject the last trustworthy crop at the current coordinates, never its old offset. */
    fun cachedFor(view:View):AppGlassSample? {
        val window=source.get()?.takeIf {it.decorView.isShown} ?: return null
        val bitmap=publishedBitmap ?: return null
        if(!publishedIsFresh())return null
        val rect=publishedRect ?: return null
        view.getLocationOnScreen(viewLocation);window.decorView.getLocationOnScreen(windowLocation)
        val x=(viewLocation[0]-windowLocation[0]-rect.left).toFloat()
        val y=(viewLocation[1]-windowLocation[1]-rect.top).toFloat()
        if(x<0 || y<0 || x+view.width>bitmap.width || y+view.height>bitmap.height) return null
        return AppGlassSample(bitmap,x,y)
    }
    fun suspendForDrag(view:GlassFloatingBubbleView) {
        if(watched.get()===view){epoch++;cancelRefresh();forgetPublishedFrame()}
    }
    fun copyBehind(view:GlassFloatingBubbleView,callback:(Bitmap?,Float,Float)->Unit) {
        if(!view.canSampleOwnBackdrop)return
        val window=source.get()
        if(window==null||!window.decorView.isShown) {forgetPublishedFrame();callback(null,0f,0f);return}
        if(view.width<=0 || !view.isAttachedToWindow || !view.isShown) return
        if(inFlight || SystemClock.uptimeMillis()-lastRequest<minCopyIntervalMillis) {deferRefresh();return}
        view.getLocationOnScreen(viewLocation);window.decorView.getLocationOnScreen(windowLocation)
        val x=viewLocation[0]-windowLocation[0];val y=viewLocation[1]-windowLocation[1]
        // A bounded surrounding crop supports continuous reprojection between position updates.
        val pad=(view.width*1.5f).roundToInt()
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
        val ticket=OwnGlassFrameTicket(epoch,committedFrame,lastRequest)
        try { PixelCopy.request(window,rect,bitmap,{result ->
            scope.launch {
            if(result==PixelCopy.SUCCESS && OwnGlassFramePolicy.acceptsResult(ticket,epoch,committedFrame,SystemClock.uptimeMillis()) && source.get()===window && view.canSampleOwnBackdrop) {
                val previous=publishedBitmap
                val sameGeometry = publishedRect==rect && publishedOffsetX==offsetX && publishedOffsetY==offsetY &&
                    publishedViewWidth==view.width && publishedViewHeight==view.height && !view.isPressed &&
                    previous!=null && previous.width==bitmap.width && previous.height==bitmap.height
                // Comparing on a background dispatcher is useful only for quiet scenes.
                // During a swipe it adds another queue hop to a frame already delivered.
                val unchanged = sameGeometry && SystemClock.uptimeMillis()>motionUntil && withContext(Dispatchers.Default) {
                    runCatching { previous!!.sameAs(bitmap) }.getOrDefault(false)
                }
                if(!OwnGlassFramePolicy.acceptsResult(ticket,epoch,committedFrame,SystemClock.uptimeMillis()) || source.get()!==window || !view.isAttachedToWindow) {
                    inFlight=false
                    val held=cachedFor(view)
                    if(held!=null) callback(held.bitmap,held.offsetX,held.offsetY)
                    else {forgetPublishedFrame();callback(null,0f,0f)}
                    if(refreshPending) deferRefresh()
                    return@launch
                }
                copyFailures=0
                val completed=ticket.copy(completedAtMillis=SystemClock.uptimeMillis())
                publishedSourceChangedAt=completed.completedAtMillis.takeIf { committedFrame>completed.committedFrame }
                if(!unchanged) {
                    publishedBitmap=bitmap;publishedRect=rect
                    publishedTicket=completed
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
                else {
                    publishedTicket=completed
                    if(!view.hasOwnBackdrop(previous))cachedFor(view)?.let {callback(it.bitmap,it.offsetX,it.offsetY)}
                }
                // No callback means no shader rebind or overlay invalidate when a page
                // redraw changed only pixels outside this small lens region.
                armExpiry()
            }
            else {
                val held=cachedFor(view)
                if(held!=null) callback(held.bitmap,held.offsetX,held.offsetY)
                else {forgetPublishedFrame();callback(null,0f,0f)}
                if(source.get()!==window) handler.post {watched.get()?.refreshBackdrop()}
                else if(view.canSampleOwnBackdrop && ++copyFailures<3) refreshPending=true
            }
            inFlight=false
            if(refreshPending) deferRefresh()
            }
        },handler) } catch (_:Exception) {
            inFlight=false
            val held=cachedFor(view)
            if(held!=null) callback(held.bitmap,held.offsetX,held.offsetY)
            else {forgetPublishedFrame();callback(null,0f,0f)}
            if(view.canSampleOwnBackdrop && ++copyFailures<3) refreshPending=true
            if(refreshPending) deferRefresh()
        }
    }
}
