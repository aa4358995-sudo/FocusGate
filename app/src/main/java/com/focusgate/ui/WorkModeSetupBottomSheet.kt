package com.focusgate.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.focusgate.R
import com.focusgate.databinding.BottomsheetWorkModeSetupBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip

/**
 * WorkModeSetupBottomSheet — Configure and launch a Work Mode session.
 * User selects: duration, app whitelist, and session intent/goal.
 */
class WorkModeSetupBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance() = WorkModeSetupBottomSheet()
    }

    private var _binding: BottomsheetWorkModeSetupBinding? = null
    private val binding get() = _binding!!

    var onConfirm: ((durationMinutes: Int, whitelist: Set<String>, intent: String) -> Unit)? = null

    private var selectedDurationMinutes = 25 // Default: Pomodoro
    private val selectedApps = mutableSetOf<String>()

    // Common duration presets
    private val durationPresets = listOf(
        Pair("25m", 25), Pair("45m", 45), Pair("1h", 60),
        Pair("90m", 90), Pair("2h", 120), Pair("Custom", -1)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomsheetWorkModeSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDurationChips()
        setupAppSelector()
        setupConfirmButton()
    }

    private fun setupDurationChips() {
        binding.chipGroupDuration.removeAllViews()
        durationPresets.forEach { (label, minutes) ->
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = minutes == selectedDurationMinutes
                setOnClickListener {
                    if (minutes == -1) {
                        showCustomDurationPicker()
                    } else {
                        selectedDurationMinutes = minutes
                        updateDurationLabel()
                    }
                }
            }
            binding.chipGroupDuration.addView(chip)
        }
    }

    private fun showCustomDurationPicker() {
        val hours = selectedDurationMinutes / 60
        val minutes = selectedDurationMinutes % 60
        TimePickerDialog(requireContext(), { _, h, m ->
            selectedDurationMinutes = h * 60 + m
            if (selectedDurationMinutes < 1) selectedDurationMinutes = 1
            updateDurationLabel()
        }, hours, minutes, true).show()
    }

    private fun updateDurationLabel() {
        val h = selectedDurationMinutes / 60
        val m = selectedDurationMinutes % 60
        binding.tvSelectedDuration.text = when {
            h == 0 -> "${m}m session"
            m == 0 -> "${h}h session"
            else -> "${h}h ${m}m session"
        }
    }

    private fun setupAppSelector() {
        binding.btnSelectApps.setOnClickListener {
            // Launch app selector
            val intent = android.content.Intent(requireContext(), AppSelectorActivity::class.java)
            intent.putStringArrayListExtra("SELECTED_APPS", ArrayList(selectedApps))
            startActivityForResult(intent, 1001)
        }
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == 1001 && resultCode == android.app.Activity.RESULT_OK) {
            val apps = data?.getStringArrayListExtra("SELECTED_APPS") ?: return
            selectedApps.clear()
            selectedApps.addAll(apps)
            binding.tvSelectedAppsCount.text = "${apps.size} apps selected"
        }
    }

    private fun setupConfirmButton() {
        updateDurationLabel()
        binding.btnStartFocus.setOnClickListener {
            val intentText = binding.etSessionGoal.text?.toString()?.trim() ?: ""
            if (intentText.isBlank()) {
                Toast.makeText(requireContext(), "Please enter your session goal", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onConfirm?.invoke(selectedDurationMinutes, selectedApps, intentText)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
