package com.nkd.screenrecorder.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.textview.MaterialTextView
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.adapter.EditAdapter
import com.nkd.screenrecorder.databinding.FragmentImagesBinding
import com.nkd.screenrecorder.utils.StorageDetailsDialog
import com.nkd.screenrecorder.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditFragment : Fragment(), MenuProvider {

    private var _binding: FragmentImagesBinding? = null
    private val binding get() = _binding!!
    private val arrayList: ArrayList<EditModel> = arrayListOf()
    private lateinit var editAdapter: EditAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentImagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            imageTitle.isGone = true
            imageStorageCard.isGone = true
            arrayList.clear()
            arrayList.add(EditModel(R.drawable.ic_edit_video, getString(R.string.edit_video)))
            arrayList.add(EditModel(R.drawable.ic_share, getString(R.string.share_label)))
            arrayList.add(EditModel(R.drawable.ic_edit_image, getString(R.string.edit_image)))
            arrayList.add(EditModel(R.drawable.ic_more_apps, getString(R.string.more_app)))
            arrayList.add(EditModel(R.drawable.ic_video_compress, getString(R.string.video_compress)))
            arrayList.add(EditModel(R.drawable.ic_gif, getString(R.string.video_to_gif)))
//            arrayList.add(EditModel(R.drawable.ic_ai_reduce_noise, "Ai Reduce Noise"))
//            arrayList.add(EditModel(R.drawable.ic_mp3, "Video to Mp3"))
            arrayList.add(EditModel(R.drawable.ic_merge_video_photo, getString(R.string.merge_video_photo)))
//            arrayList.add(EditModel(R.drawable.ic_auto_captions, "Audio Captions"))

            editAdapter = EditAdapter(arrayList)
            editAdapter.onItemClick = { model, position ->
                when (position) {
                    0 -> {
                        findNavController().navigate(
                            EditFragmentDirections.actionMenuEditToVideoTrimmerFragment(
                                isTrim = false,
                                isEditVideo = true,
                                isVideoCompress = false
                            )
                        )
                    }

                    1 -> {
                        /*Share Application*/
                        val playStoreLink =
                            "https://play.google.com/store/apps/details?id=${requireActivity().packageName}"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_app_subject))
                            putExtra(
                                Intent.EXTRA_TEXT,
                                getString(R.string.share_app_message, playStoreLink)
                            )
                        }
                        requireActivity().startActivity(
                            Intent.createChooser(
                                shareIntent,
                                getString(R.string.share_via)
                            )
                        )
                    }

                    2 -> {
                        findNavController().navigate(
                            EditFragmentDirections.actionMenuEditToImageEditFragment()
                        )
                    }

                    3 -> {
                        val consoleUrl = "https://play.google.com/store/apps/developer?id=NKD+BVR+Labs&hl=en_IN"
                        val intent = Intent(Intent.ACTION_VIEW, consoleUrl.toUri())
                        startActivity(intent)

                    }

                    4 -> {
                        findNavController().navigate(
                            EditFragmentDirections.actionMenuEditToVideoTrimmerFragment(
                                isTrim = false,
                                isEditVideo = false,
                                isVideoCompress = true
                            )
                        )
                    }

                    5 -> {
                        findNavController().navigate(
                            EditFragmentDirections.actionMenuEditToVideoTrimmerFragment(
                                isTrim = false,
                                isEditVideo = false,
                                isVideoCompress = false,
                                isVideoToGIF = true
                            )
                        )
                    }

                    6 -> {
                        findNavController().navigate(
                            EditFragmentDirections.actionMenuEditToVideoTrimmerFragment(
                                isTrim = false,
                                isEditVideo = false,
                                isVideoCompress = false,
                                isVideoToGIF = false,
                                isVideoMerger = true
                            )
                        )
                    }
                }
            }
            recyclerView.adapter = editAdapter
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            (requireActivity() as MainActivity).hideTopCardView(true)
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
        val actionView = menu.findItem(R.id.storageStaticsMenu).actionView
        val storageStaticsTextView =
            actionView?.findViewById<MaterialTextView>(R.id.storageStaticsTextView)
        if (storageStaticsTextView != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val (total, used, free) = Utils.getStorageInfo()

                val totalText = Utils.formatFileSizeToGB(total)
                val usedText = Utils.formatFileSizeToGB(used)

                withContext(Dispatchers.Main) {
                    storageStaticsTextView.text = getString(R.string.storage_used_total, usedText, totalText)

                    storageStaticsTextView.setOnClickListener {
                        showStorageDetailsDialog(total, used, free)
                    }
                }
            }
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.action_settings -> {
                findNavController().navigate(R.id.settingFragment)
                return true
            }

            else -> false
        }
    }

    private fun showStorageDetailsDialog(total: Long, used: Long, free: Long) {
        StorageDetailsDialog.show(requireContext(), total, used, free)
    }
}

data class EditModel(val imageDrawable: Int, val title: String)