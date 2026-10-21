package com.nkd.screenrecorder.fragments

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.adapter.ImageGridAdapter
import com.nkd.screenrecorder.databinding.FragmentImagesBinding
import com.nkd.screenrecorder.model.ImageItem
import com.nkd.screenrecorder.utils.Utils
import com.nkd.screenrecorder.viewmodel.ImageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageEditFragment : Fragment() {

    private var _binding: FragmentImagesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageViewModel by viewModels()
    private lateinit var adapter: ImageGridAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapter()
        setupObservers()
        setupSelectionToolbar()
        updateStorageInfo()
        viewModel.fetchImages()
    }

    private fun setupAdapter() {
        adapter = ImageGridAdapter(
            list = emptyList(),
            onItemClick = { image ->
                if (viewModel.selectionMode.value == true) {
                    viewModel.toggleSelection(image.id)
                } else {
                    openImagePreview(image)
                }
            }, onItemLongClick = { image ->
                if (viewModel.selectionMode.value == false) {
                    viewModel.enableSelectionMode()
                    viewModel.toggleSelection(image.id)
                }
            })
    }

    private fun openImagePreview(clickedImage: ImageItem) {
        val imageList = viewModel.imageList.value ?: return
        val position = imageList.indexOfFirst { it.id == clickedImage.id }

        if (position != -1) {
            val action = ImageEditFragmentDirections.actionImageEditFragmentToImagePreviewFragment(
                imageList = imageList.toTypedArray(), gifList = null, currentPosition = position
            )
            findNavController().navigate(action)
        }
    }

    private fun setupObservers() {
        viewModel.imageList.observe(viewLifecycleOwner) { imageList ->
            binding.apply {
                if (imageList.isEmpty()) {
                    recyclerView.isGone = true
                    noDataView.isVisible = true
                } else {
                    noDataView.isGone = true
                    recyclerView.isVisible = true

                    val gridLayoutManager = GridLayoutManager(requireContext(), 3)
                    adapter.updateItems(imageList)
                    recyclerView.layoutManager = gridLayoutManager
                    recyclerView.adapter = adapter
                }
            }
        }

        viewModel.selectionMode.observe(viewLifecycleOwner) { selectionMode ->
            adapter.setSelectionMode(selectionMode)
            binding.toolbarSelect.selectionToolbar.isVisible = selectionMode
        }

        viewModel.selectedItems.observe(viewLifecycleOwner) { selectedItems ->
            adapter.setSelectedItems(selectedItems)
            binding.toolbarSelect.selectionCountText.text =
                getString(R.string.selected_count, selectedItems.size)
        }

        viewModel.deleteResult.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { success ->
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_images_deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_images_delete_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { _ -> }
    }

    private fun setupSelectionToolbar() {
        binding.apply {
            toolbarSelect.closeSelectionButton.setOnClickListener {
                viewModel.clearSelection()
            }
            toolbarSelect.selectAllButton.setOnClickListener {
                viewModel.selectAll()
            }
            toolbarSelect.shareButton.setOnClickListener {
                shareSelectedImages()
            }
            toolbarSelect.deleteButton.setOnClickListener {
                showDeleteConfirmationDialog()
            }
        }
    }

    private fun shareSelectedImages() {
        val uris = viewModel.getSelectedImageUris()
        if (uris.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.msg_no_images_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val shareIntent = Intent().apply {
            if (uris.size == 1) {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uris[0])
                type = "image/*"
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                type = "image/*"
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_images)))
    }

    private fun showDeleteConfirmationDialog() {
        val count = viewModel.getSelectedCount()
        if (count == 0) {
            Toast.makeText(
                requireContext(),
                getString(R.string.msg_no_images_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_delete_title))
            .setMessage(getString(R.string.dialog_delete_message, count))
            .setPositiveButton(getString(R.string.btn_delete)) { dialog, _ ->
                viewModel.deleteSelectedImages()
                dialog.dismiss()
            }.setNegativeButton(getString(R.string.btn_cancel)) { dialog, _ ->
                dialog.dismiss()
            }.show()
    }

    private fun updateStorageInfo() {
        binding.apply {
            lifecycleScope.launch(Dispatchers.IO) {
                val (total, used, _) = Utils.getStorageInfo()
                val totalText = Utils.formatFileSizeToGB(total)
                val usedText = Utils.formatFileSizeToGB(used)

                withContext(Dispatchers.Main) {
                    storageStaticsTextView.text = getString(R.string.storage_used_total, usedText, totalText)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.fetchImages()

        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
            hideSystemUI()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            viewModel.isLoading.removeObservers(viewLifecycleOwner)
            (requireActivity() as MainActivity).hideTopCardView(true)
            (requireActivity() as MainActivity).hideBottomNavAndFab(true)
            showSystemBars()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun hideSystemUI() {
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(
            requireActivity().window, requireActivity().window.decorView
        )
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }
}