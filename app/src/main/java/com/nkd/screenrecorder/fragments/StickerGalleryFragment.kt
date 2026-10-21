package com.nkd.screenrecorder.fragments

import com.nkd.screenrecorder.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nkd.screenrecorder.utils.dpToPx
import java.io.File
import java.io.FileOutputStream

class StickerGalleryFragment : Fragment() {

    var onPicked: ((Drawable) -> Unit)? = null

    private var saveImported = true
    private var manageMode = false
    private val saved = mutableListOf<File>()
    private var galleryAdapter: GalleryAdapter? = null

    private val dir get() = File(requireContext().filesDir, "imported_stickers").apply { mkdirs() }

    private val picker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            val bmp = decodeBitmap(uri)
            if (bmp == null) {
                Toast.makeText(requireContext(), getString(R.string.failed_load_image), Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (saveImported) {
                FileOutputStream(File(dir, "sticker_${System.currentTimeMillis()}.png")).use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                reload()
            }
            onPicked?.invoke(BitmapDrawable(resources, bmp))
        }

    private fun reload() {
        saved.clear()
        saved.addAll(dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList())
        galleryAdapter?.notifyDataSetChanged()
    }

    private fun decodeBitmap(uri: Uri, maxSize: Int = 1024): Bitmap? = try {
        val cr = requireContext().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) { null }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        reload()

        val check = CheckBox(ctx).apply {
            text = getString(R.string.save_imported_sticker)
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = true
            setOnCheckedChangeListener { _, c -> saveImported = c }
        }
        val manage = TextView(ctx).apply {
            text = "MANAGE"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(14.dpToPx(), 5.dpToPx(), 14.dpToPx(), 5.dpToPx())
            background = GradientDrawable().apply {
                cornerRadius = 30.dpToPx().toFloat()
                setStroke(1.dpToPx(), Color.WHITE)
            }
            setOnClickListener {
                manageMode = !manageMode
                text = if (manageMode) "DONE" else "MANAGE"
                galleryAdapter?.notifyDataSetChanged()
            }
        }
        val header = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
            addView(check, LinearLayout.LayoutParams(0, -2, 1f))
            addView(manage)
        }

        galleryAdapter = GalleryAdapter()
        val rv = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 4)
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            clipToPadding = false
            adapter = galleryAdapter
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(rv, LinearLayout.LayoutParams(-1, -1))
        }
    }

    private inner class GalleryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = saved.size + 1   // 0 = "+" cell

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val iv = ImageView(p.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, 64.dpToPx())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val iv = h.itemView as ImageView
            if (pos == 0) {
                iv.background = GradientDrawable().apply {
                    cornerRadius = 8.dpToPx().toFloat()
                    setStroke(2.dpToPx(), 0xFF8E8E93.toInt(), 8.dpToPx().toFloat(), 5.dpToPx().toFloat())
                }
                iv.setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
                iv.setImageResource(android.R.drawable.ic_input_add)
                iv.setColorFilter(0xFF8E8E93.toInt())
                iv.alpha = 1f
                iv.setOnClickListener {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            } else {
                val file = saved[pos - 1]
                iv.setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                iv.background = null
                iv.clearColorFilter()
                Glide.with(iv).load(file).into(iv)
                iv.alpha = if (manageMode) 0.5f else 1f
                iv.setOnClickListener {
                    if (manageMode) {
                        file.delete()
                        reload()
                    } else {
                        BitmapFactory.decodeFile(file.absolutePath)?.let {
                            onPicked?.invoke(BitmapDrawable(resources, it))
                        }
                    }
                }
            }
        }
    }
}