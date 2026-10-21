package com.nkd.screenrecorder.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.textview.MaterialTextView
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.activities.VideoPlayerActivity
import com.nkd.screenrecorder.activities.VideoTrimmingActivity
import com.nkd.screenrecorder.adapter.VideoListAdapter
import com.nkd.screenrecorder.databinding.FragmentVideoBinding
import com.nkd.screenrecorder.fragments.SettingFragment.Companion.AUDIO_SOURCE
import com.nkd.screenrecorder.fragments.SettingFragment.Companion.PREFS_NAME
import com.nkd.screenrecorder.model.VideoItem
import com.nkd.screenrecorder.services.RecordScreenService
import com.nkd.screenrecorder.utils.VideoQualityPrefs
import com.nkd.screenrecorder.utils.RecordingOptionsSheet
import com.nkd.screenrecorder.utils.StorageDetailsDialog
import com.nkd.screenrecorder.utils.Utils
import com.nkd.screenrecorder.utils.VideoBottomSheetDialog
import com.nkd.screenrecorder.viewmodel.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class VideoFragment : Fragment(), MenuProvider {

    private var _binding: FragmentVideoBinding? = null
    private lateinit var adapter: VideoListAdapter
    private lateinit var preference: SharedPreferences
    private val binding get() = _binding!!

    private lateinit var videoViewModel: VideoViewModel

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            videoViewModel.fetchRecordedVideosFromSAF(requireContext())
        } else {
            showEmptyState()
        }
    }
    private val newRecordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isAdded) {
                videoViewModel.fetchRecordedVideosFromSAF(requireContext())
            }
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
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        preference =
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        videoViewModel = ViewModelProvider(this)[VideoViewModel::class.java]

        binding.apply {
            // Video quality (VideoQualityPrefs — same key/sheet as Settings): "Auto · 1080p" …
            videoQualityValue.text = VideoQualityPrefs.homeSummary(requireContext())
            videoQualityCard.setOnClickListener {
                RecordingOptionsSheet.showVideoQuality(requireContext()) {
                    _binding?.videoQualityValue?.text = VideoQualityPrefs.homeSummary(requireContext())
                }
            }
            // Mute / Microphone — nothing saved = Microphone (same as the service)
            val audioSources = resources.getStringArray(R.array.audio_source)
            audioSourceValue.text = audioSources[SettingFragment.audioSourceIndex(preference)]
            audioQualityCard.setOnClickListener {
                val alertBuilder = AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.select_audio_source))
                    .setSingleChoiceItems(
                        R.array.audio_source,
                        SettingFragment.audioSourceIndex(preference),
                    ) { dialogInterface, which ->
                        preference.edit {
                            putInt(AUDIO_SOURCE, which)
                        }
                        audioSourceValue.text = audioSources[which]
                        dialogInterface.dismiss()

                    }
                val dialog = alertBuilder.create()
                dialog.show()
            }
        }
        listenOnViewModel()
    }

    private fun hasValidDirectory(): Boolean {
        val uriString = preference.getString(MainActivity.KEY_DIRECTORY_URI, null) ?: return false
        val uri = uriString.toUri()
        return requireContext().contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
    }

    private fun showEmptyState() {
        _binding?.apply {
            progressBar.isGone = true
            recyclerView.isGone = true
            noDataView.isVisible = true
        }
    }

    private fun fetchVideos() {
        if (!hasValidDirectory()) {
            showEmptyState()
            return
        }
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

    fun listenOnViewModel() {
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

                    adapter = VideoListAdapter(it)
                    recyclerView.adapter = adapter
                    adapter.onItemClick = { videoItem ->
                        val intent = Intent(requireContext(), VideoPlayerActivity::class.java)
                        intent.data = videoItem.uri
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(intent)
                    }
                    adapter.onShareClick = { videoItem ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/*"
                            putExtra(Intent.EXTRA_STREAM, videoItem.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_video_via)))

                    }
                    adapter.onEditClick = { videoItem ->
                        findNavController().navigate(
                            VideoFragmentDirections.actionVideoItemToEditVideoActivity(
                                videoItem.uri.toString()
                            )
                        )
                    }
                    adapter.onMoreClick = { videoItem ->
                        val videoBottomSheetDialog = VideoBottomSheetDialog()
                        videoBottomSheetDialog.show(childFragmentManager, "VIDEO")
                        videoBottomSheetDialog.onRenameClick = {
                            showRenameDialog(requireContext(), videoItem) {
                                videoViewModel.fetchRecordedVideosFromSAF(requireContext()) // reload list
                            }
                        }
                        videoBottomSheetDialog.onDeleteClick = {
                            val alertDialogBuilder = AlertDialog.Builder(requireContext())
                                .setTitle(getString(R.string.delete_file_title))
                                .setMessage(getString(R.string.delete_file_message))
                                .setPositiveButton(getString(R.string.btn_delete)) { dialog, which ->
                                    dialog.dismiss()
                                    val isDeleted =
                                        videoViewModel.deleteVideo(requireContext(), videoItem.uri)
                                    if (isDeleted) {
                                        Toast.makeText(
                                            context,
                                            getString(R.string.deleted_successfully),
                                            Toast.LENGTH_SHORT
                                        )
                                            .show()
                                        videoViewModel.fetchRecordedVideosFromSAF(
                                            requireActivity()
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            getString(R.string.delete_failed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                .setNegativeButton(getString(R.string.btn_cancel)) { dialog, which ->
                                    dialog.dismiss()
                                }
                            alertDialogBuilder.create().show()
                        }
                        videoBottomSheetDialog.onTrimClick = {
                            val intent = Intent(requireContext(), VideoTrimmingActivity::class.java)
                            intent.putExtra("Url", videoItem.uri.toString())
                            startActivity(intent)
                        }
                    }
                }
            }
        }
    }

    fun showRenameDialog(context: Context, videoItem: VideoItem, onRenamed: () -> Unit) {

        val editText = EditText(context).apply {
            setText(videoItem.name.substringBeforeLast(".")) // name without extension
            setSelection(text.length)
            hint = getString(R.string.enter_new_name)
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.rename_video_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.rename)) { dialog, _ ->

                val inputName = editText.text.toString().trim()

                if (inputName.isEmpty()) {
                    Toast.makeText(context, getString(R.string.name_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val safeName = if (inputName.endsWith(".mp4")) inputName else "$inputName.mp4"

                val success = videoViewModel.renameVideo(context, videoItem.uri, safeName)

                if (success) {
                    Toast.makeText(context, getString(R.string.renamed_successfully), Toast.LENGTH_SHORT).show()
                    onRenamed() // refresh list
                } else {
                    Toast.makeText(context, getString(R.string.rename_failed), Toast.LENGTH_SHORT).show()
                }

                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        // May have been changed in Settings / the toolbox
        _binding?.videoQualityValue?.text = VideoQualityPrefs.homeSummary(requireContext())
        fetchVideos()
        val filter = IntentFilter(RecordScreenService.NEW_RECORDING_ADDED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                newRecordingReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            requireContext().registerReceiver(newRecordingReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            requireContext().unregisterReceiver(newRecordingReceiver)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}