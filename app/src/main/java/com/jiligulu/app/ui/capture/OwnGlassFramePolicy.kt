package com.jiligulu.app.ui.capture

/** PixelCopy is asynchronous; a current-coordinate crop is not necessarily current content. */
internal data class OwnGlassFrameTicket(val epoch:Long,val committedFrame:Long,val startedAtMillis:Long,
    val completedAtMillis:Long = startedAtMillis)
internal object OwnGlassFramePolicy {
    const val MAX_COPY_LATENCY_MILLIS = 250L
    fun holdMillis(targetFps:Int):Long {
        val fps=targetFps.coerceIn(15,120)
        return (3L*((1000L+fps-1)/fps)).coerceIn(100,250)
    }
    /** PixelCopy completes independently of the page's frame rate. */
    fun acceptsResult(ticket:OwnGlassFrameTicket,epoch:Long,committedFrame:Long,nowMillis:Long):Boolean {
        if(ticket.epoch!=epoch || committedFrame<ticket.committedFrame)return false
        val distance=committedFrame-ticket.committedFrame
        return distance==0L || nowMillis-ticket.startedAtMillis in 0..MAX_COPY_LATENCY_MILLIS
    }
    /** Keep one completed material until its replacement; never switch off between page frames. */
    fun canDisplay(ticket:OwnGlassFrameTicket,epoch:Long,committedFrame:Long,nowMillis:Long,targetFps:Int,
        sourceChangedAtMillis:Long?=null):Boolean {
        if(ticket.epoch!=epoch || committedFrame<ticket.committedFrame)return false
        return committedFrame==ticket.committedFrame || nowMillis-(sourceChangedAtMillis ?: ticket.completedAtMillis) in 0..holdMillis(targetFps)
    }
}
