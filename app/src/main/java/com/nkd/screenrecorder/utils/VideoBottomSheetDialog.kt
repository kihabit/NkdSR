package com.nkd.screenrecorder.utils

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nkd.screenrecorder.databinding.LayoutVideoBottomSheetBinding

class VideoBottomSheetDialog : BottomSheetDialogFragment() {

    private lateinit var binding: LayoutVideoBottomSheetBinding

    var onRenameClick: (() -> Unit)? = null
    var onDeleteClick: (() -> Unit)? = null

    var onTrimClick: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = LayoutVideoBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            renameLayout.setOnClickListener {
                onRenameClick?.invoke()
                dialog?.dismiss()
            }
            deleteLayout.setOnClickListener {
                onDeleteClick?.invoke()
                dialog?.dismiss()
            }
            // ✅ Trim option temporarily disabled — commented out, not removed,
            // matching layout_video_bottom_sheet.xml's commented-out trimLayout/divider2.
            // trimLayout.setOnClickListener {
            //     onTrimClick?.invoke()
            //     dialog?.dismiss()
            // }
        }
    }


}