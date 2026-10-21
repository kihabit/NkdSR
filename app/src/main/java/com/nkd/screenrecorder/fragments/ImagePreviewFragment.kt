package com.nkd.screenrecorder.fragments

import com.nkd.screenrecorder.R

import android.app.Activity.RESULT_OK
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.adapter.ImagePreviewAdapter
import com.nkd.screenrecorder.databinding.FragmentImagePreviewBinding
import com.nkd.screenrecorder.model.MediaItem
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.model.AspectRatio
import com.yalantis.ucrop.view.CropImageView


class ImagePreviewFragment : Fragment() {

    private var _binding: FragmentImagePreviewBinding? = null
    private val binding get() = _binding!!
    private val args: ImagePreviewFragmentArgs by navArgs()
    private lateinit var adapter: ImagePreviewAdapter
    private var isControlsVisible = true
    private val imageList = arrayListOf<MediaItem>()
    private val activityResultLauncher =
        registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                val resultUri = UCrop.getOutput(result.data!!)
            } else if (result.resultCode == UCrop.RESULT_ERROR) {
                val cropError = UCrop.getError(result.data!!)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImagePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewPager()
        setupControls()
    }

    private fun setupViewPager() {
        imageList.addAll(
            if (args.imageList != null) {
                args.imageList!!.toList()
            } else {
                args.gifList!!.toList()
            }
        )
        val currentPosition = args.currentPosition

        adapter = ImagePreviewAdapter(
            images = imageList, onImageClick = { toggleControls() })

        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)

        // Update position indicator
        updatePositionIndicator(currentPosition, imageList.size)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updatePositionIndicator(position, imageList.size)
            }
        })
    }

    private fun setupControls() {
        if (args.imageList == null) {
            binding.cropButton.isGone = true
            binding.editButton.isGone = true
        }
        binding.closeButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.shareButton.setOnClickListener {
            shareCurrentImage()
        }

        binding.deleteButton.setOnClickListener {
            deleteCurrentImage()
        }
        binding.cropButton.setOnClickListener {
            val sourceUri = args.imageList!![binding.viewPager.currentItem].uri

            val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)

            val cursor = requireContext().contentResolver.query(
                sourceUri,
                projection,
                null,
                null,
                null
            )

            var relativePath = "Pictures/" // fallback

            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex =
                        it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    relativePath = it.getString(columnIndex)
                }
            }
            val fileName = "crop_${System.currentTimeMillis()}.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath) // 👈 SAME FOLDER
            }

            val destinationUri = requireContext().contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            val options = UCrop.Options()
            options.setFreeStyleCropEnabled(true)
            options.setAspectRatioOptions(
                2,
                AspectRatio("WOW", 1f, 2f),
                AspectRatio("MUCH", 3f, 4f),
                AspectRatio(
                    "RATIO",
                    CropImageView.DEFAULT_ASPECT_RATIO,
                    CropImageView.DEFAULT_ASPECT_RATIO
                ),
                AspectRatio("SO", 16f, 9f),
                AspectRatio("ASPECT", 1f, 1f)
            )
            options.withAspectRatio(
                CropImageView.DEFAULT_ASPECT_RATIO,
                CropImageView.DEFAULT_ASPECT_RATIO
            )
            UCrop.of(
                args.imageList!![binding.viewPager.currentItem].uri,
                destinationUri!!
            )
                .withOptions(options)
                .start(requireActivity(), activityResultLauncher)
        }
        binding.editButton.setOnClickListener {
            findNavController().navigate(
                ImagePreviewFragmentDirections.actionImagePreviewFragmentToEditImageFragment(
                    imageList[binding.viewPager.currentItem].uri.toString()
                )
            )
        }
    }

    private fun toggleControls() {
        isControlsVisible = !isControlsVisible
        binding.topBar.isVisible = isControlsVisible
        binding.bottomBar.isVisible = isControlsVisible
    }

    private fun updatePositionIndicator(position: Int, total: Int) {
        binding.positionText.text = getString(R.string.position_counter, position + 1, total)
    }

    private fun shareCurrentImage() {
        val currentPosition = binding.viewPager.currentItem
        val currentImage = args.imageList?.get(currentPosition)

        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_STREAM, currentImage?.uri)
            type = "image/*"
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.share_image)))
    }

    private fun deleteCurrentImage() {
        val currentPosition = binding.viewPager.currentItem
        val currentImage = if (args.imageList != null) {
            args.imageList!![currentPosition]
        } else {
            args.gifList!![currentPosition]
        }
        val title = if (args.imageList != null) {
            getString(R.string.delete_image_title)
        } else {
            getString(R.string.delete_gif_title)
        }
        val message = if (args.imageList != null) {
            getString(R.string.delete_image_message)
        } else {
            getString(R.string.delete_gif_message)
        }
        AlertDialog.Builder(requireContext()).setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_delete)) { dialog, _ ->
                deleteImage(currentImage)
                dialog.dismiss()
            }.setNegativeButton(getString(R.string.btn_cancel)) { dialog, _ ->
                dialog.dismiss()
            }.show()

    }

    private fun deleteImage(image: MediaItem) {
        try {
            val deleted = requireContext().contentResolver.delete(image.uri, null, null)
            if (deleted > 0) {
                Toast.makeText(
                    requireContext(), getString(R.string.image_deleted), Toast.LENGTH_SHORT
                ).show()

                if (args.imageList != null) {
                    // Go back if this was the last image
                    if (args.imageList!!.size == 1) {
                        findNavController().navigateUp()
                    } else {
                        imageList.remove(image)
                        // You might want to update the args or adapter here
                        adapter.notifyItemRemoved(imageList.indexOf(image))
                    }
                } else {
                    if (args.gifList!!.size == 1) {
                        findNavController().navigateUp()
                    } else {
                        imageList.remove(image)
                        adapter.notifyItemRemoved(imageList.indexOf(image))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ImagePreviewFragment", " ${e.message}")
            Toast.makeText(
                requireContext(), getString(R.string.image_delete_failed), Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
            (requireActivity() as MainActivity).hideToolbar(false)
            hideSystemUI()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            (requireActivity() as MainActivity).hideTopCardView(true)
            (requireActivity() as MainActivity).hideBottomNavAndFab(true)
            (requireActivity() as MainActivity).hideToolbar(true)
            showSystemBars()
        }
    }

    private fun hideSystemUI() {
        // Access activity's window through requireActivity()
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API (30+)
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Legacy API (below 30)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(
            requireActivity().window, requireActivity().window.decorView
        )
        // Show both the status bar and the navigation bar
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }
}