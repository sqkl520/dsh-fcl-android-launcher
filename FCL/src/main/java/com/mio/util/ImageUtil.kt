package com.mio.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import com.tungsten.fclcore.util.Logging
import java.io.File
import java.util.Optional
import java.util.logging.Level

class ImageUtil {
    companion object {
        @JvmStatic
        fun getSize(path: String): Pair<Int, Int> {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            return Pair(options.outWidth, options.outHeight)
        }

        @JvmStatic
        fun getBitmapMemorySize(path: String): Long {
            val size = getSize(path)
            return size.first * size.second * 4L
        }

        @JvmStatic
        fun load(path: String): Optional<Bitmap> {
            if (!File(path).exists() or (getBitmapMemorySize(path) > 104857600)) {
                return Optional.empty<Bitmap>()
            }
            return Optional.ofNullable(BitmapFactory.decodeFile(path))
        }

        @JvmStatic
        fun loadInto(view: View, drawable: Drawable) {
            Glide.with(view).load(drawable).centerCrop()
                .into(object : CustomViewTarget<View, Drawable>(view) {
                    override fun onResourceCleared(placeholder: Drawable?) {
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        // [round31/s10-eh-10] 原先是空方法：Glide 加载失败时既不写日志也不通知调用方，
                        // 于是"启动页/主界面背景空白"在日志里没有任何线索（三处调用点都靠这个回调
                        // 设背景：SplashActivity / DshSetupActivity / DshMainActivity）。
                        // 只补**可观测性**，不改视觉降级策略：失败时 view.background 保持 XML 默认
                        // 背景，那本身就是兜底。也不碰 onResourceReady / onResourceCleared 的行为。
                        // 日志里只用 getClass().name（不像 view.toString() 那样可能是被 stub 的
                        // 方法），并带上 errorDrawable 的有无，够排障定位。
                        Logging.LOG.log(
                            Level.WARNING,
                            "ImageUtil.loadInto: background load failed (view=" +
                                view.javaClass.name + ", errorDrawable=" +
                                (if (errorDrawable == null) "null" else errorDrawable.javaClass.name) + ")"
                        )
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable>?
                    ) {
                        view.background = resource
                    }

                })

        }
    }
}