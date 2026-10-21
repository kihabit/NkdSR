package com.nkd.screenrecorder.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.textview.MaterialTextView
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.adapter.ImageGridAdapter
import com.nkd.screenrecorder.databinding.FragmentImagesBinding
import com.nkd.screenrecorder.model.ImageItem
import com.nkd.screenrecorder.utils.StorageDetailsDialog
import com.nkd.screenrecorder.utils.Utils
import com.nkd.screenrecorder.viewmodel.ImageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class ImageFragment : Fragment(), MenuProvider {

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

        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        setupAdapter()
        setupObservers()
        setupSelectionToolbar()
        updateStorageInfo()

        // Fetch images
        viewModel.fetchImages()
    }

    private fun setupAdapter() {
        adapter = ImageGridAdapter(
            list = emptyList(),
            onItemClick = { image ->
                if (viewModel.selectionMode.value == true) {
                    viewModel.toggleSelection(image.id)
                } else {
                    // Normal click - open image preview
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
            val action = ImageFragmentDirections.actionImageItemToImagePreviewFragment(
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

                    // Set up GridLayoutManager with 3 columns
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

            // Hide/show regular toolbar based on selection mode
            // You might want to hide your fragment title or toolbar here
        }

        viewModel.selectedItems.observe(viewLifecycleOwner) { selectedItems ->
            adapter.setSelectedItems(selectedItems)
            binding.toolbarSelect.selectionCountText.text = getString(R.string.selected_count, selectedItems.size)
        }

        viewModel.deleteResult.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { success ->
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_images_deleted),
                        Toast.LENGTH_SHORT
                    )
                        .show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.msg_images_delete_failed), Toast.LENGTH_SHORT)
                        .show()
                }
            }

        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            // You can show/hide a progress bar here if needed
            // binding.progressBar.isVisible = isLoading
        }
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
            Toast.makeText(requireContext(), getString(R.string.msg_no_images_selected), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), getString(R.string.msg_no_images_selected), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext()).setTitle(getString(R.string.dialog_delete_title))
            .setMessage(resources.getQuantityString(R.plurals.dialog_delete_images, count, count))
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
                true
            }

            else -> false
        }
    }

    private fun showStorageDetailsDialog(total: Long, used: Long, free: Long) {
        StorageDetailsDialog.show(requireContext(), total, used, free)
    }

    override fun onResume() {
        super.onResume()
        // Refresh images when returning to the fragment
        viewModel.fetchImages()
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            viewModel.isLoading.removeObservers(viewLifecycleOwner)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}