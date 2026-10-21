package com.nkd.screenrecorder.adapter

import android.graphics.drawable.Drawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.nkd.screenrecorder.fragments.EmojiStickerFragment
import com.nkd.screenrecorder.fragments.StickerGalleryFragment
import com.nkd.screenrecorder.utils.EmojiData

class StickerPagerAdapter(
    activity: FragmentActivity,
    private val onPicked: (Drawable) -> Unit
) : FragmentStateAdapter(activity) {

    override fun getItemCount() = 1 + EmojiData.categories.size

    override fun createFragment(position: Int): Fragment =
        if (position == 0) StickerGalleryFragment().also { it.onPicked = onPicked }
        else EmojiStickerFragment.newInstance(position - 1).also { it.onPicked = onPicked }
}