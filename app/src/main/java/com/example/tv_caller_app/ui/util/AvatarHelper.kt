package com.example.tv_caller_app.ui.util

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.tv_caller_app.R

object AvatarHelper {

    private val presetAvatarMap = mapOf(
        "blue" to R.drawable.avatar_blue,
        "green" to R.drawable.avatar_green,
        "red" to R.drawable.avatar_red,
        "purple" to R.drawable.avatar_purple,
        "orange" to R.drawable.avatar_orange,
        "teal" to R.drawable.avatar_teal
    )

    fun loadAvatar(
        imageView: ImageView,
        avatarUrl: String?,
        fallbackDrawable: Int = R.drawable.avatar_circle,
        initialsView: TextView? = null,
        username: String? = null
    ) {
        when {
            avatarUrl.isNullOrBlank() -> {
                imageView.setImageResource(0)
                imageView.setBackgroundResource(fallbackDrawable)
                if (initialsView != null) {
                    initialsView.text = username?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    initialsView.visibility = View.VISIBLE
                }
            }
            avatarUrl.startsWith("preset:") -> {
                val avatarName = avatarUrl.removePrefix("preset:")
                val drawableRes = presetAvatarMap[avatarName]
                if (drawableRes != null) {
                    imageView.setBackgroundResource(0)
                    imageView.setImageResource(drawableRes)
                } else {
                    imageView.setImageResource(0)
                    imageView.setBackgroundResource(fallbackDrawable)
                    if (initialsView != null) {
                        initialsView.text = username?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                        initialsView.visibility = View.VISIBLE
                    }
                }
                initialsView?.visibility = View.GONE
            }
            else -> {
                imageView.setBackgroundResource(0)
                initialsView?.visibility = View.GONE
                Glide.with(imageView.context)
                    .load(avatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .circleCrop()
                    .placeholder(fallbackDrawable)
                    .into(imageView)
            }
        }
    }
}
