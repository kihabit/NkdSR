package com.nkd.screenrecorder.utils

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.widget.doOnTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.adapter.TextViewFontAdapter
import com.nkd.screenrecorder.databinding.BottomSheetDialogTextviewFontBinding

data class TextFontModel(val name: String, val font: Int, val assetFileName: String = "")

class TextFontBottomSheetDialog : BottomSheetDialogFragment() {
    private lateinit var binding: BottomSheetDialogTextviewFontBinding
    private val arrayList: ArrayList<TextFontModel> = ArrayList()
    private lateinit var adapter: TextViewFontAdapter
    var onTextViewClick: ((TextFontModel) -> Unit)? = null
    var onTextChange: ((String) -> Unit)? = null

    var onBottomSheetClosed: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = BottomSheetDialogTextviewFontBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            arrayList.add(TextFontModel("Default", R.font.roboto_serif))
            arrayList.add(TextFontModel("Aladin", R.font.aladin))
            arrayList.add(TextFontModel("Aguafina", R.font.aguafina_script))

            adapter = TextViewFontAdapter(arrayList)
//            recyclerView.adapter = adapter

            adapter.onItemClick = {
                onTextViewClick?.invoke(it)
            }
            enterTextEditText.doOnTextChanged { text, start, before, count ->
                if (text.isNullOrEmpty() || text.isBlank()) {
                    onTextChange?.invoke(getString(R.string.enter_text))
                } else {
                    onTextChange?.invoke(text.toString())
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }
}