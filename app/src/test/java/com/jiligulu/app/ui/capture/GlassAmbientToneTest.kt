package com.jiligulu.app.ui.capture

import org.junit.Assert.*
import org.junit.Test

class GlassAmbientToneTest {
    @Test fun edgeTextCannotBecomeAHighContrastStripeAcrossTheMissingCentre() {
        val plain=IntArray(1000){0xffeeeeee.toInt()}
        val text=plain.copyOf().apply {for(i in 0..99)this[i]=0xff000000.toInt()}
        val first=GlassAmbientTone.estimate(plain)
        val second=GlassAmbientTone.estimate(text)
        for(shift in listOf(0,8,16))assertTrue(kotlin.math.abs(((first ushr shift) and 255)-((second ushr shift) and 255))<=16)
    }
    @Test fun excludedPixelsContributeNeitherOwnIconColorNorBlackMask() {
        val safe=0xff88bbdd.toInt()
        assertEquals(GlassAmbientTone.estimate(IntArray(100){safe}),
            GlassAmbientTone.estimate(IntArray(1000){if(it<100)safe else 0}))
        assertEquals(GlassAmbientTone.NEUTRAL,GlassAmbientTone.estimate(IntArray(1000)))
    }
}
