package com.nkd.screenrecorder.fragments

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.adapter.DraftAdapter
import com.nkd.screenrecorder.adapter.VideoTrimmerListAdapter
import com.nkd.screenrecorder.databinding.FragmentVideoBinding
import com.nkd.screenrecorder.fragments.SettingFragment.Companion.PREFS_NAME
import com.nkd.screenrecorder.utils.DraftManager
import com.nkd.screenrecorder.viewmodel.VideoViewModel

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class VideoTrimmerFragment : Fragment() {

    private var _binding: FragmentVideoBinding? = null
    private lateinit var adapter: VideoTrimmerListAdapter
    private lateinit var preference: SharedPreferences
    private val binding get() = _binding!!
    private val videoViewModel: VideoViewModel by viewModels()
    private val navArgs: VideoTrimmerFragmentArgs by navArgs()
    private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            videoViewModel.fetchRecordedVideosFromSAF(requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preference =
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        binding.apply {
            videoQualityCard.isGone = true
            audioQualityCard.isGone = true

            fetchDataWithPermissions()
        }
        fetchVideoData()

        // ✅ NAYA — sirf Edit Video flow mein drafts dikhao
        if (navArgs.isEditVideo) {
            setupDraftSection()
        }
    }

    private fun fetchDataWithPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                videoViewModel.fetchRecordedVideosFromSAF(requireContext())
            }
        } else {
            videoViewModel.fetchRecordedVideosFromSAF(requireContext())
        }
    }

    fun fetchVideoData() {
        binding.apply {
            videoViewModel.videoLiveData.observe(viewLifecycleOwner) { it ->
                if (it.isEmpty()) {
                    recyclerView.isGone = true
                    noDataView.isVisible = true
                    progressBar.isGone = true
                } else {
                    noDataView.isGone = true
                    recyclerView.isVisible = true
                    progressBar.isGone = true

                    adapter = VideoTrimmerListAdapter(it)
                    recyclerView.adapter = adapter
                    adapter.onItemClick = { videoItem ->
                        if (navArgs.isTrim) {
                            findNavController().navigate(
                                VideoTrimmerFragmentDirections.actionVideoTrimmerFragmentToVideoTrimmingActivity(
                                    videoItem.uri.toString()
                                )
                            )
                        } else if (navArgs.isEditVideo) {
                            findNavController().navigate(
                                VideoTrimmerFragmentDirections.actionVideoTrimmerFragmentToEditVideoActivity(
                                    videoItem.uri.toString()
                                )
                            )
                        } else if (navArgs.isVideoCompress) {
                            findNavController().navigate(
                                VideoTrimmerFragmentDirections.actionVideoTrimmerFragmentToVideoCompressorFragment(
                                    videoItem.uri.toString()
                                )
                            )
                        } else if (navArgs.isVideoToGIF) {
                            findNavController().navigate(
                                VideoTrimmerFragmentDirections.actionVideoTrimmerFragmentToVideoTrimmingActivity(
                                    Url = videoItem.uri.toString(),
                                    isVideoToGIF = true
                                )
                            )
                        } else if (navArgs.isVideoMerger) {
                            findNavController().navigate(
                                VideoTrimmerFragmentDirections.actionVideoTrimmerFragmentToVideoMergerFragment(
                                    arrayOf(videoItem.uri.toString())
                                )
                            )
                        } else if (navArgs.addSticker) {
                            findNavController().navigate(
                                VideoTrimmerFragmentDirections.actionVideoTrimmerFragmentToAddStickerFragment(
                                    videoItem.uri.toString()
                                )
                            )
                        } else {
                            findNavController().previousBackStackEntry?.savedStateHandle?.set(
                                "trimmed_video_uri",
                                videoItem.uri.toString()
                            )
                            findNavController().popBackStack()
                        }
                    }
                }
            }
        }
    }

    // ✅ NAYA — Draft list load karke horizontal RecyclerView mein bind karta hai.
    // Draft khaali ho to section hide rehta hai.
    private fun setupDraftSection() {
        if (_binding == null) return
        val drafts = DraftManager.recentDrafts(requireContext(), limit = 5)

        binding.apply {
            if (drafts.isEmpty()) {
                draftSectionTitle.isGone = true
                draftRecyclerView.isGone = true
                return@apply
            }

            draftSectionTitle.isVisible = true
            draftRecyclerView.isVisible = true

            val draftAdapter = DraftAdapter(drafts)
            draftRecyclerView.adapter = draftAdapter

            draftAdapter.onItemClick = { entry ->
                findNavController().navigate(
                    VideoTrimmerFragmentDirections.actionVideoTrimmerFragmentToEditVideoActivity(
                        entry.videoUrl,
                        entry.draftId
                    )
                )
            }

            draftAdapter.onItemLongClick = { entry ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.delete_draft_title))
                    .setMessage(getString(R.string.delete_draft_message))
                    .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                        DraftManager.removeEntry(requireContext(), entry.draftId)
                        dialog.dismiss()
                        setupDraftSection()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
            hideSystemUI()
            fetchDataWithPermissions()


            if (navArgs.isEditVideo) {
                setupDraftSection()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
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