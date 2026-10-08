package com.jiligulu.app.ui.capture

/** A low-frequency material tone, never an attempt to reconstruct occluded letters or lines. */
internal object GlassAmbientTone {
    const val NEUTRAL:Int = -0x111609 // 0xffeee9f7
    fun estimate(pixels:IntArray):Int {
        val hist=IntArray(48)
        var count=0
        val stride=(pixels.size/4096).coerceAtLeast(1)
        for(index in pixels.indices step stride) {
            val color=pixels[index]
            if((color ushr 24)<128)continue // exclusion/transparent pixels contribute nothing
            hist[(color ushr 20) and 15]++
            hist[16+((color ushr 12) and 15)]++
            hist[32+((color ushr 4) and 15)]++
            count++
        }
        if(count==0)return NEUTRAL
        fun channel(offset:Int):Int {
            val trim=count/8
            var lower=trim;var upper=count-trim
            var total=0L;var weight=0
            for(bin in 0..15) {
                val n=hist[offset+bin]
                val used=(minOf(n,upper)-lower.coerceAtMost(n)).coerceAtLeast(0)
                total+=used*(bin*16+8).toLong();weight+=used
                lower=(lower-n).coerceAtLeast(0);upper=(upper-n).coerceAtLeast(0)
            }
            return if(weight==0)240 else (total/weight).toInt().coerceIn(0,255)
        }
        return (255 shl 24) or (channel(0) shl 16) or (channel(16) shl 8) or channel(32)
    }
}
