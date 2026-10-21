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
import com.nkd.screenrecorder.adapter.GIFGridAdapter
import com.nkd.screenrecorder.databinding.FragmentImagesBinding
import com.nkd.screenrecorder.model.GifItem
import com.nkd.screenrecorder.model.MediaListItem
import com.nkd.screenrecorder.utils.StorageDetailsDialog
import com.nkd.screenrecorder.utils.Utils
import com.nkd.screenrecorder.viewmodel.ImageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GIFFragment : Fragment(), MenuProvider {
    private var _binding: FragmentImagesBinding? = null
    private lateinit var adapter: GIFGridAdapter
    private val binding get() = _binding!!

    private val viewModel: ImageViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentImagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        binding.apply {
            imageTitle.text = getString(R.string.title_gif)
            viewModel.fetchGIF()
            lifecycleScope.launch(Dispatchers.IO) {
                val (total, used, free) = Utils.getStorageInfo()
                val totalText = Utils.formatFileSizeToGB(total)
                val usedText = Utils.formatFileSizeToGB(used)

                withContext(Dispatchers.Main) {
                    storageStaticsTextView.text = getString(R.string.storage_used_total, usedText, totalText)
                }
            }
            setupAdapter()
            setupSelectionToolbar()
        }
    }

    fun setupAdapter() {
        adapter = GIFGridAdapter(emptyList())

        binding.recyclerView.adapter = adapter
        adapter.onItemClick = { gifItem, position ->
            if (viewModel.selectionMode.value == true) {
                viewModel.toggleSelection(gifItem.id)
            } else {
                val tempList = arrayListOf<GifItem>()
                adapter.list.forEach { list ->
                    if (list is MediaListItem.Media) {
                        tempList.add(list.item as GifItem)
                    }
                }
                val tempPosition = tempList.indexOf(gifItem)
                findNavController().navigate(
                    GIFFragmentDirections.actionGifItemToImagePreviewFragment(
                        imageList = null,
                        gifList = tempList.toTypedArray(),
                        currentPosition = tempPosition
                    )
                )
            }
        }
        adapter.onLongClick = { gif, position ->
            if (viewModel.selectionMode.value == false) {
                viewModel.enableSelectionMode()
                viewModel.toggleSelection(gif.id)
            }
        }

        viewModel.gifList.observe(viewLifecycleOwner) { list ->
            binding.apply {
                if (list.isEmpty()) {
                    recyclerView.isGone = true
                    noDataView.isVisible = true
                    noDataTextView.text = getString(R.string.msg_no_gif_found)
                } else {
                    noDataView.isGone = true
                    recyclerView.isVisible = true
                    adapter.updateItems(list)
                    val gridLayoutManager = GridLayoutManager(requireContext(), 3)

                    gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return when (list[position]) {
                                is MediaListItem.Header -> 3
                                is MediaListItem.Media -> 1
                            }
                        }
                    }
                    binding.recyclerView.layoutManager = gridLayoutManager
                }
            }
        }

        viewModel.isGifLoading.observe(viewLifecycleOwner) { }

        viewModel.selectionMode.observe(viewLifecycleOwner) { selectionMode ->
            adapter.setSelectionMode(selectionMode)
            binding.toolbarSelect.selectionToolbar.isVisible = selectionMode
        }

        viewModel.selectedItems.observe(viewLifecycleOwner) { selectedItems ->
            adapter.setSelectedItems(selectedItems)
            binding.toolbarSelect.selectionCountText.text =
                getString(R.string.selected_count, selectedItems.size)
        }

        viewModel.deleteGIFResult.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { success ->
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_gif_deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_gif_delete_failed),
                        Toast.LENGTH_SHORT
                    ).show()
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

    private fun setupSelectionToolbar() {
        binding.apply {
            toolbarSelect.closeSelectionButton.setOnClickListener {
                viewModel.clearSelection()
            }
            toolbarSelect.selectAllButton.setOnClickListener {
                viewModel.selectAllGif()
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
        val uris = viewModel.getSelectedGIFUris()
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
                viewModel.deleteSelectedImages(isGif = true)
                dialog.dismiss()
            }.setNegativeButton(getString(R.string.btn_cancel)) { dialog, _ ->
                dialog.dismiss()
            }.show()
    }

    override fun onPause() {
        super.onPause()
        viewModel.clearSelection()
        viewModel.deleteGIFResult.removeObservers(viewLifecycleOwner)
    }
}