package com.jiligulu.app.ui.littleworld

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal data class RoomDrawOffer(val round:Int,val revision:Int,val id:Int,val player:Int) {
    val wire get()="$round|$revision|$id|$player"
}
internal enum class RoomDrawResolution { ACCEPTED, DECLINED, CANCELLED, TIMEOUT, BUSY, STALE }

/** Consent changes only the outcome. A regular STATE cannot smuggle in an agreed draw. */
internal object RoomDrawRules {
    fun accepts(round:Int,revision:Int,offer:RoomDrawOffer)=offer.round==round&&offer.revision==revision&&
        revision<Int.MAX_VALUE&&offer.id>0&&offer.player in 1..2
    fun acceptsResult(round:Int,revision:Int,offer:RoomDrawOffer,localConsented:Boolean,result:RoomControl.DrawResult)=
        localConsented&&accepts(round,revision,offer)&&result.offer==offer&&result.resolution==RoomDrawResolution.ACCEPTED&&
            result.resultingRevision==revision+1
}

/** One host-authoritative negotiation shared by both board games and both transports. */
internal class RoomDrawNegotiation(
    private val hosting:()->Boolean, private val player:()->Int?, private val round:()->Int?,
    private val revision:()->Int, private val playing:()->Boolean, private val available:()->Boolean,
    private val suspended:()->Boolean, private val send:(RoomControl)->Unit,
    private val present:(RoomDrawOffer?,Boolean)->Unit, private val finish:(Int)->Unit
) {
    private val main=Handler(Looper.getMainLooper())
    private var sequence=0
    private val seen=IntArray(3)
    private var consent=false
    private var timer:Runnable?=null
    private var deadline:RoomNegotiationDeadline?=null
    var pending:RoomDrawOffer?=null;private set
    fun request() {
        val local=player()?:return;val r=round()?:return
        if(!available()||!playing()||pending!=null||sequence==Int.MAX_VALUE||revision()==Int.MAX_VALUE)return
        val offer=RoomDrawOffer(r,revision(),++sequence,local);seen[local]=offer.id
        bind(offer,true)
        send(if(hosting())RoomControl.DrawPending(offer)else RoomControl.DrawRequest(offer))
    }
    fun respond(accept:Boolean) {
        val offer=pending?:return;val local=player()?:return
        if(offer.player==local||!available()||!valid(offer))return
        // Once sent, agreement remains binding until the host resolves a retraction.
        // A late cancel must not make peers disagree about an already committed draw.
        consent=consent||accept
        if(hosting())resolve(offer,if(accept)RoomDrawResolution.ACCEPTED else RoomDrawResolution.DECLINED)
        else send(RoomControl.DrawResponse(offer,accept))
    }
    fun cancel() {
        val offer=pending?:return
        if(offer.player!=player())return
        if(hosting())resolve(offer,RoomDrawResolution.CANCELLED)else send(RoomControl.DrawResponse(offer,false))
    }
    fun receive(control:RoomControl) {
        val local=player()?:return
        when(control) {
            is RoomControl.DrawRequest -> {
                if(!hosting())throw LanProtocolException()
                val offer=control.offer
                if(!valid(offer)){send(RoomControl.DrawResult(offer,RoomDrawResolution.STALE,revision()));return}
                if(offer.player!=3-local)throw LanProtocolException()
                if(offer.id<=seen[offer.player])return
                seen[offer.player]=offer.id
                val old=pending
                if(old!=null&&old.player==local&&available()) {
                    // Both people independently clicked 和棋 at this unchanged position.
                    send(RoomControl.DrawPending(old));resolve(old,RoomDrawResolution.ACCEPTED)
                } else if(old==null&&available()) {bind(offer,false);send(RoomControl.DrawPending(offer))}
                else send(RoomControl.DrawResult(offer,RoomDrawResolution.BUSY,revision()))
            }
            is RoomControl.DrawPending -> {
                if(hosting())throw LanProtocolException()
                val offer=control.offer
                if(!valid(offer)||pending==offer)return
                val own=pending
                if(offer.player==local && own!=offer)throw LanProtocolException()
                if(offer.player!=local&&offer.id<=seen[offer.player])return
                seen[offer.player]=offer.id
                val alreadyAgreed=own!=null&&own.player==local&&own.round==offer.round&&own.revision==offer.revision
                bind(offer,offer.player==local||alreadyAgreed)
                if(alreadyAgreed&&offer.player!=local)send(RoomControl.DrawResponse(offer,true))
            }
            is RoomControl.DrawResponse -> {
                if(!hosting())throw LanProtocolException()
                val offer=pending?:return
                if(control.offer!=offer||!valid(offer))return
                if(offer.player==local)resolve(offer,if(control.accept)RoomDrawResolution.ACCEPTED else RoomDrawResolution.DECLINED)
                else if(!control.accept)resolve(offer,RoomDrawResolution.CANCELLED)
            }
            is RoomControl.DrawResult -> {
                if(hosting())throw LanProtocolException()
                val offer=pending?:return
                if(control.offer!=offer)return
                if(control.resolution==RoomDrawResolution.ACCEPTED) {
                    if(!RoomDrawRules.acceptsResult(round()?:return,revision(),offer,consent,control)||!playing())throw LanProtocolException()
                    clear();finish(control.resultingRevision)
                } else if(control.resultingRevision==revision()||control.resolution==RoomDrawResolution.STALE)clear()
            }
            else -> Unit
        }
    }
    private fun valid(offer:RoomDrawOffer)=playing()&&RoomDrawRules.accepts(round()?:0,revision(),offer)
    private fun bind(offer:RoomDrawOffer,agreed:Boolean) {
        clear();pending=offer;consent=agreed;present(offer,offer.player==player())
        deadline=RoomNegotiationDeadline(if(hosting())20_000 else 25_000,SystemClock.uptimeMillis(),suspended())
        timer=object:Runnable {override fun run(){
            if(pending!=offer)return
            if(!valid(offer)){clear();return}
            val paused=suspended();val remaining=deadline?.tick(SystemClock.uptimeMillis(),paused)?:return
            if(remaining==0L&&!paused){if(hosting())resolve(offer,RoomDrawResolution.TIMEOUT)else {send(RoomControl.DrawResponse(offer,false));clear()}}
            else main.postDelayed(this,if(paused)1000 else remaining.coerceIn(1,1000))
        }}.also {main.postDelayed(it,1000)}
    }
    private fun resolve(offer:RoomDrawOffer,result:RoomDrawResolution) {
        if(!hosting()||pending!=offer||!valid(offer))return
        val next=if(result==RoomDrawResolution.ACCEPTED)revision()+1 else revision()
        send(RoomControl.DrawResult(offer,result,next));clear()
        if(result==RoomDrawResolution.ACCEPTED)finish(next)
    }
    fun refreshDeadline(){deadline?.tick(SystemClock.uptimeMillis(),suspended())}
    fun clear(){timer?.let(main::removeCallbacks);timer=null;deadline=null;pending=null;consent=false;present(null,false)}
    fun reset(){clear();sequence=0;seen.fill(0)}
}
