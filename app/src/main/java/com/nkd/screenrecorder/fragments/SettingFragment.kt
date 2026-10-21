package com.nkd.screenrecorder.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.databinding.FragmentSettingBinding
import com.nkd.screenrecorder.services.NotificationPanelService
import com.nkd.screenrecorder.services.SSViewService
import com.nkd.screenrecorder.utils.VideoQualityPrefs
import com.nkd.screenrecorder.utils.RecordingOptionsSheet
import com.nkd.screenrecorder.utils.DBHelper
import com.nkd.screenrecorder.utils.DirectoryPreferenceManager
import com.nkd.screenrecorder.utils.LanguageDialog
import com.nkd.screenrecorder.utils.LocaleHelper
import com.nkd.screenrecorder.utils.PrefsHelper
import com.nkd.screenrecorder.utils.RateUsPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingFragment : Fragment() {
    private var _binding: FragmentSettingBinding? = null
    private val binding get() = _binding!!
    private lateinit var preference: SharedPreferences
    private lateinit var directoryManager: DirectoryPreferenceManager

    companion object {
        const val PREFS_NAME = "ScreenRecorderPrefs"
        const val SHOW_COUNT_DOWN = "show_count_down"
        const val COUNT_DOWN_SECONDS = "count_down_seconds"
        const val KEEP_RECORDING = "keep_recording"
        const val HIDE_FLOAT_VIEW = "hide_float_view"
        const val AUDIO_SOURCE = "audio_source"
        /** "Notification toolbar" (always-on IDLE panel) — default ON. */
        const val NOTIFICATION_TOOLBAR = "notification_toolbar"

        /** R.array.audio_source: 0 Mute · 1 Microphone. */
        const val AUDIO_SOURCE_MUTE = 0
        const val AUDIO_SOURCE_MICROPHONE = 1

        const val COUNTDOWN_MIN_SECONDS = 1
        const val COUNTDOWN_MAX_SECONDS = 10

        /** Saved audio source; anything but Mute records the microphone
         *  (the old 3rd item "Default" also recorded the mic). */
        fun audioSourceIndex(prefs: SharedPreferences): Int =
            if (prefs.getInt(AUDIO_SOURCE, -1) == AUDIO_SOURCE_MUTE) AUDIO_SOURCE_MUTE else AUDIO_SOURCE_MICROPHONE

        /** Saved countdown, always within 1–10 (old out-of-range values are clamped). */
        fun countdownSeconds(prefs: SharedPreferences): Int =
            prefs.getInt(COUNT_DOWN_SECONDS, 3).coerceIn(COUNTDOWN_MIN_SECONDS, COUNTDOWN_MAX_SECONDS)

        /**
         * "Countdown time" dialog shared by Settings and the toolbox. Parses safely and keeps the
         * dialog open with an error for empty / non-number / out-of-range input.
         */
        fun showCountdownDialog(
            context: Context,
            prefs: SharedPreferences,
            windowType: Int? = null,
            onSaved: (Int) -> Unit
        ) {
            val container = FrameLayout(context)
            val editText = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = context.getString(R.string.seconds_hint)
                setText(countdownSeconds(prefs).toString())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(50, 20, 50, 20) }
            }
            container.addView(editText)

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.enter_countdown))
                .setView(container)
                .setPositiveButton(context.getString(R.string.btn_save), null)
                .setNegativeButton(context.getString(R.string.btn_cancel), null)
                .create()
            windowType?.let { dialog.window?.setType(it) }
            dialog.setOnShowListener {
                // Own click handler → the dialog stays open while the input is invalid
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = editText.text.toString().trim().toIntOrNull()
                    if (value == null || value !in COUNTDOWN_MIN_SECONDS..COUNTDOWN_MAX_SECONDS) {
                        editText.error = context.getString(
                            R.string.countdown_range_error, COUNTDOWN_MIN_SECONDS, COUNTDOWN_MAX_SECONDS
                        )
                        return@setOnClickListener
                    }
                    prefs.edit { putInt(COUNT_DOWN_SECONDS, value) }
                    onSaved(value)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preference = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        directoryManager = DirectoryPreferenceManager(requireContext())

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        binding.apply {

            // ── Video Quality + Frame Rate (VideoQualityPrefs — same keys/sheets as Home) ──
            refreshRecordingOptions()
            val openVideoQuality = View.OnClickListener {
                RecordingOptionsSheet.showVideoQuality(requireContext()) { refreshRecordingOptions() }
            }
            videoQualitySection.setOnClickListener(openVideoQuality)
            selectedVideoQuality.setOnClickListener(openVideoQuality)

            val openFrameRate = View.OnClickListener {
                RecordingOptionsSheet.showFrameRate(requireContext()) { refreshRecordingOptions() }
            }
            frameRateSection.setOnClickListener(openFrameRate)
            frameRateQuality.setOnClickListener(openFrameRate)

            // ── Save Location ──────────────────────────────────────
            val openSaveLocation = View.OnClickListener {
                (requireActivity() as? MainActivity)?.changeOutputDirectory()
            }
            locationPath.setOnClickListener(openSaveLocation)
            selectLocationPath.setOnClickListener(openSaveLocation)
            val path = directoryManager.getDirectoryPath()
            selectedPathText.text = if (path != null) getString(R.string.current_path, path) else getString(R.string.no_directory_selected)

            // ── Switches (tapping the whole row toggles the switch → same listener) ──
            switchForCountDown.isChecked = preference.getBoolean(SHOW_COUNT_DOWN, true)
            switchForKeepRecording.isChecked = preference.getBoolean(KEEP_RECORDING, false)
            switchForHideFloatingBall.isChecked = preference.getBoolean(HIDE_FLOAT_VIEW, true)
            countdownSeconds.isVisible = switchForCountDown.isChecked

            switchForCountDown.setOnCheckedChangeListener { _, isChecked ->
                preference.edit { putBoolean(SHOW_COUNT_DOWN, isChecked) }
                countdownSeconds.isVisible = isChecked
            }
            switchForKeepRecording.setOnCheckedChangeListener { _, isChecked ->
                preference.edit { putBoolean(KEEP_RECORDING, isChecked) }
            }
            switchForHideFloatingBall.setOnCheckedChangeListener { _, isChecked ->
                preference.edit { putBoolean(HIDE_FLOAT_VIEW, isChecked) }
                // Applies right away if a recording is already running
                SSViewService.applyHideWhileRecording(requireContext())
            }
            countdownBeforeStart.setOnClickListener { switchForCountDown.toggle() }
            keepRecordingInOffScreen.setOnClickListener { switchForKeepRecording.toggle() }
            hideFloatingBall.setOnClickListener { switchForHideFloatingBall.toggle() }

            // ── Notification toolbar (default ON). OFF = no IDLE panel; the RECORDING
            // notification still shows during a recording (required by Android).
            switchForNotificationToolbar.isChecked = preference.getBoolean(NOTIFICATION_TOOLBAR, true)
            switchForNotificationToolbar.setOnCheckedChangeListener { _, isChecked ->
                preference.edit { putBoolean(NOTIFICATION_TOOLBAR, isChecked) }
                if (isChecked) {
                    NotificationPanelService.start(requireContext())
                } else {
                    NotificationPanelService.stop(requireContext())
                }
            }
            notificationToolbar.setOnClickListener { switchForNotificationToolbar.toggle() }

            // ── Countdown Seconds (1–10) ───────────────────────────
            countdownSeconds.text = getString(R.string.countdown_time, countdownSeconds(preference))
            countdownSeconds.setOnClickListener {
                showCountdownDialog(requireContext(), preference) { value ->
                    countdownSeconds.text = getString(R.string.countdown_time, value)
                }
            }

            // ── Audio Source (Mute / Microphone) ───────────────────
            val audioSources = resources.getStringArray(R.array.audio_source)
            selectedAudioSource.text = audioSources[audioSourceIndex(preference)]
            val openAudioSource = View.OnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.select_audio_source))
                    .setSingleChoiceItems(R.array.audio_source, audioSourceIndex(preference)) { dlg, which ->
                        preference.edit { putInt(AUDIO_SOURCE, which) }
                        selectedAudioSource.text = audioSources[which]
                        dlg.dismiss()
                    }.create().show()
            }
            audioSourceSection.setOnClickListener(openAudioSource)
            selectedAudioSource.setOnClickListener(openAudioSource)

            // ════════════════════════════════════════════════════════
            // ── 🌍 LANGUAGE SELECTION  ← NEW ADDED HERE
            // ════════════════════════════════════════════════════════
            val languages = LanguageDialog.languages
            val languageCodes = LanguageDialog.languageCodes

            val savedLang = LocaleHelper.getLanguage(requireContext())
            val currentIndex = languageCodes.indexOf(savedLang)
            currentLanguageText.text = if (currentIndex != -1) languages[currentIndex] else languages[0]

            languageSection.setOnClickListener {
                LanguageDialog.show(requireActivity(), cancelable = true) {
                    requireActivity().recreate()
                }
            }
            // ════════════════════════════════════════════════════════

            // ── ⭐ Rate Us ─────────────────────────────────────────
            rateUsSection.setOnClickListener {
                RateUsPopup.showRateDialog(requireActivity())
            }

            // ── Smart Rate Us Logic ────────────────────────────────
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dbHelper = DBHelper(requireContext())
                    val existingRating = dbHelper.getRating()
                    val count = PrefsHelper.getCounter(requireContext())
                    val created = dbHelper.getCreated()
                    val days = PrefsHelper.getDateDiff(created)

                    withContext(Dispatchers.Main) {
                        if (existingRating < 5 && (created == null || (count > 5 && days > 3))) {
                            PrefsHelper.resetCounter(requireContext())
                            RateUsPopup.showRateDialog(requireActivity())
                        }
                        PrefsHelper.incrementCounter(requireContext())
                    }
                } catch (e: Exception) {
                    // Log.e("SettingFragment", "Error in rate us logic", e)
                }
            }
        }
    }

    /** Chips: "Auto · 1080p" / "720p" and "Auto · 30 FPS" / "60 FPS". */
    private fun refreshRecordingOptions() {
        val b = _binding ?: return
        b.selectedVideoQuality.text = VideoQualityPrefs.qualitySummary(requireContext())
        b.frameRateQuality.text = VideoQualityPrefs.frameRateSummary(requireContext())
    }

    override fun onResume() {
        super.onResume()
        refreshRecordingOptions()   // may have been changed on Home / in the toolbox
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            (requireActivity() as MainActivity).hideTopCardView(true)
            (requireActivity() as MainActivity).hideBottomNavAndFab(true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
