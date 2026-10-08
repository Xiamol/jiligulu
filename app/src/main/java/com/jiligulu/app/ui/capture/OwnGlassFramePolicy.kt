package com.jiligulu.app.ui.capture

/** PixelCopy is asynchronous; a current-coordinate crop is not necessarily current content. */
internal data class OwnGlassFrameTicket(val epoch:Long,val committedFrame:Long,val startedAtMillis:Long)
internal object OwnGlassFramePolicy {
    fun maxAgeMillis(targetFps:Int)=(2000L/targetFps.coerceIn(15,120)).coerceIn(17,50)
    fun isFresh(ticket:OwnGlassFrameTicket,epoch:Long,committedFrame:Long,nowMillis:Long,targetFps:Int):Boolean {
        if(ticket.epoch!=epoch)return false
        val distance=committedFrame-ticket.committedFrame
        // A truly static source has no newer content, so keeping its texture needs no timer.
        if(distance==0L)return true
        return distance in 1..2&&nowMillis-ticket.startedAtMillis in 0..maxAgeMillis(targetFps)
    }
}
