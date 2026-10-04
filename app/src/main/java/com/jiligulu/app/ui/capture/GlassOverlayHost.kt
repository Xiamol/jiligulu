package com.jiligulu.app.ui.capture

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.jiligulu.app.R
import kotlin.math.roundToInt

/** A real Window enables Android's localized background blur without reading screen pixels. */
internal class GlassOverlayHost(private val context:Context,private val manager:WindowManager) {
    private var dialog:Dialog?=null
    private var standalone:View?=null
    private var materialSide=0
    private fun background(window:android.view.Window,side:Int) {
        if(materialSide==side) return
        val shape=GradientDrawable().apply { setColor(Color.argb(2,255,255,255));cornerRadius=side*.28f }
        window.setBackgroundDrawable(InsetDrawable(shape,(side*.08f).roundToInt()))
        if(Build.VERSION.SDK_INT>=31) window.setBackgroundBlurRadius((side*.12f).roundToInt())
        materialSide=side
    }

    fun attach(view:View,params:WindowManager.LayoutParams) {
        if(Build.VERSION.SDK_INT>=31) {
            val candidate=Dialog(context,R.style.Theme_Jiligulu_GlassOverlay)
            candidate.setCancelable(false)
            candidate.setCanceledOnTouchOutside(false)
            try {
                candidate.setContentView(view,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                val window=checkNotNull(candidate.window)
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.decorView.setPadding(0,0,0,0)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
                window.attributes=params
                background(window,params.width)
                candidate.show(); dialog=candidate
                return
            } catch (_:Exception) {
                runCatching { candidate.dismiss() }
                (view.parent as? ViewGroup)?.removeView(view)
            }
        }
        manager.addView(view,params);standalone=view
    }

    fun update(params:WindowManager.LayoutParams) {
        val window=dialog?.window
        if(window!=null) {
            window.attributes=params
            background(window,params.width)
        } else standalone?.let { manager.updateViewLayout(it,params) }
    }

    fun visible(show:Boolean) {
        dialog?.let { if(show) it.show() else it.hide() }
        standalone?.visibility=if(show) View.VISIBLE else View.INVISIBLE
    }

    fun detach() { dialog?.dismiss();dialog=null;standalone?.let {manager.removeView(it)};standalone=null }
}
