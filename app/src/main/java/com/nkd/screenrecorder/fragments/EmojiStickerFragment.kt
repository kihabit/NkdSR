package com.nkd.screenrecorder.fragments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nkd.screenrecorder.utils.EmojiData
import com.nkd.screenrecorder.utils.dpToPx

class EmojiStickerFragment : Fragment() {

    var onPicked: ((Drawable) -> Unit)? = null

    companion object {
        fun newInstance(categoryIndex: Int) =
            EmojiStickerFragment().apply { arguments = bundleOf("cat" to categoryIndex) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val (title, emojis) = EmojiData.categories[requireArguments().getInt("cat")]

        val header = TextView(ctx).apply {
            text = title
            textSize = 14f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 4.dpToPx())
        }
        val rv = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 6)
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
            clipToPadding = false
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = emojis.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                    val tv = TextView(p.context).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()
                        )
                        gravity = Gravity.CENTER
                        textSize = 26f
                    }
                    return object : RecyclerView.ViewHolder(tv) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val emoji = emojis[pos]
                    (h.itemView as TextView).text = emoji
                    h.itemView.setOnClickListener {
                        onPicked?.invoke(BitmapDrawable(resources, emojiToBitmap(emoji)))
                    }
                }
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(rv, LinearLayout.LayoutParams(-1, -1))
        }
    }

    /** 1024px isliye ki enableStickerResize() max size = sticker native size se limit karta hai. */
    private fun emojiToBitmap(emoji: String, size: Int = 1024): Bitmap {
        val bmp = createBitmap(size, size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.8f
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        Canvas(bmp).drawText(emoji, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return bmp
    }
}