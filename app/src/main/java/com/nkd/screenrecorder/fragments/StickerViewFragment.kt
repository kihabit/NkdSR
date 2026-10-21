package com.nkd.screenrecorder.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.adapter.StickerViewAdapter
import com.nkd.screenrecorder.databinding.FragmentStickerViewBinding

class StickerViewFragment(
    private val type: String,
    val onStickerClicked: ((sicker: Int) -> Unit)
) : Fragment() {
    private lateinit var binding: FragmentStickerViewBinding
    private lateinit var adapter: StickerViewAdapter
    private val arrayList: ArrayList<Int> = arrayListOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentStickerViewBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            arrayList.clear()
            if (type == "1") {
                //arrayList.add(R.drawable.ic_smile)
                //arrayList.add(R.drawable.ic_smile2)
                arrayList.add(R.drawable.ic_birthday)
                arrayList.add(R.drawable.ic_birthday2)
                arrayList.add(R.drawable.ic_dog)
                arrayList.add(R.drawable.ic_heart)
                arrayList.add(R.drawable.ic_tankyou2)
                arrayList.add(R.drawable.emoji)
                arrayList.add(R.drawable.ic_tankyou2)
                arrayList.add(R.drawable.ic_star)
                arrayList.add(R.drawable.ic_hello)
                arrayList.add(R.drawable.ic_goodnight)
            }
            adapter = StickerViewAdapter(arrayList) {
                onStickerClicked.invoke(arrayList[it])
            }
            recyclerView.adapter = adapter
        }
    }
}
