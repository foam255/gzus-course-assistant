package com.gzus.courseassistant.ui.more

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gzus.courseassistant.BuildConfig
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.data.update.UpdateInfo
import com.gzus.courseassistant.databinding.DialogUpdateProgressBinding
import com.gzus.courseassistant.databinding.FragmentMoreBinding
import com.gzus.courseassistant.ui.calendar.CalendarActivity
import com.gzus.courseassistant.ui.main.MainActivity
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MoreFragment : Fragment() {
    private var binding: FragmentMoreBinding? = null
    private var syncing = false
    private var checkingUpdate = false
    private var downloadingUpdate = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentMoreBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewBinding = binding ?: return
        viewBinding.versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        viewBinding.calendarButton.setOnClickListener {
            startActivity(Intent(requireContext(), CalendarActivity::class.java))
        }
        viewBinding.reminderButton.setOnClickListener { showReminderDialog() }
        viewBinding.syncButton.setOnClickListener { syncAll() }
        viewBinding.firstWeekButton.setOnClickListener { showFirstWeekDatePicker() }
        viewBinding.checkUpdateButton.setOnClickListener { checkForUpdates() }
        viewBinding.reloginButton.setOnClickListener {
            (activity as? MainActivity)?.showLogin()
        }
        viewBinding.logoutButton.setOnClickListener { confirmLogout() }
        viewBinding.clearButton.setOnClickListener { confirmClear() }
        viewBinding.aboutButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.about_title)
                .setMessage(
                    buildString {
                        append(getString(R.string.about_message))
                        append("\n\n")
                        append(getString(R.string.about_author))
                        append('\n')
                        append(
                            getString(
                                R.string.about_github,
                                getString(R.string.github_repository_url),
                            ),
                        )
                        append("\n\n")
                        append(getString(R.string.about_star))
                    },
                )
                .setNeutralButton(R.string.star_on_github) { _, _ ->
                    openGitHubRepository()
                }
                .setPositiveButton(R.string.close, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository().state.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(state: AppState) {
        val viewBinding = binding ?: return
        viewBinding.accountStatus.text = getString(
            if (state.sessionValid) R.string.more_connected else R.string.more_disconnected,
        )
        viewBinding.syncStatus.text = getString(
            R.string.last_sync,
            UiUtils.syncLabel(state.lastScheduleSync, getString(R.string.never_synced)),
        )
        val term = resolveTerm(state)
        val override = state.firstWeekStarts[term.label]
        val firstWeekStart = term.effectiveStartDate(override)
        val currentWeek = term.currentWeek(
            today = LocalDate.now(),
            firstWeekStart = firstWeekStart,
        )
        viewBinding.termStatus.text = getString(
            R.string.more_term_status,
            "${term.year}-${term.year + 1} 第${term.number}学期",
        )
        viewBinding.firstWeekStatus.text = getString(
            R.string.more_first_week_status,
            firstWeekStart.format(DATE_FORMATTER),
            currentWeek,
            getString(
                if (override == null) R.string.week_source_auto
                else R.string.week_source_manual,
            ),
        )
        viewBinding.syncButton.isEnabled = !syncing
        updateUpdateButton()
    }

    private fun checkForUpdates() {
        if (checkingUpdate || downloadingUpdate) return
        checkingUpdate = true
        updateUpdateButton()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val update = requireApp().container.updateManager.checkLatest()
                if (update == null) {
                    (activity as? MainActivity)?.showMessage(
                        getString(R.string.update_latest),
                    )
                } else {
                    showUpdateAvailable(update)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                (activity as? MainActivity)?.showMessage(
                    listOfNotNull(
                        getString(R.string.update_check_failed),
                        error.message?.takeIf(String::isNotBlank),
                    ).joinToString("\n"),
                )
            } finally {
                checkingUpdate = false
                updateUpdateButton()
            }
        }
    }

    private fun showUpdateAvailable(update: UpdateInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                getString(
                    R.string.update_available_title,
                    update.versionName,
                ),
            )
            .setMessage(
                getString(
                    R.string.update_available_message,
                    BuildConfig.VERSION_NAME,
                    update.versionName,
                ),
            )
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(R.string.download_update) { _, _ ->
                downloadUpdate(update)
            }
            .show()
    }

    private fun downloadUpdate(update: UpdateInfo) {
        if (downloadingUpdate) return
        val manager = requireApp().container.updateManager
        val host = activity ?: return
        val dialogBinding = DialogUpdateProgressBinding.inflate(layoutInflater)
        dialogBinding.updateStatus.text = getString(
            R.string.downloading_update,
            update.versionName,
        )
        dialogBinding.updateProgress.setProgressCompat(0, false)
        dialogBinding.updatePercent.text = getString(R.string.update_download_percent, 0)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        dialog.show()
        downloadingUpdate = true
        updateUpdateButton()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = manager.download(update) { percent ->
                    host.runOnUiThread {
                        if (host.isFinishing) return@runOnUiThread
                        dialogBinding.updateProgress.setProgressCompat(percent, true)
                        dialogBinding.updatePercent.text = getString(
                            R.string.update_download_percent,
                            percent,
                        )
                    }
                }
                dialog.dismiss()
                if (!manager.verifyApk(file, update.versionName)) {
                    (activity as? MainActivity)?.showMessage(
                        getString(R.string.update_signature_invalid),
                    )
                    return@launch
                }
                if (!manager.canInstallPackages()) {
                    (activity as? MainActivity)?.showMessage(
                        getString(R.string.update_allow_install),
                    )
                    manager.openUnknownSourcesSettings()
                } else {
                    manager.install(file)
                }
            } catch (error: CancellationException) {
                dialog.dismiss()
                throw error
            } catch (error: Exception) {
                dialog.dismiss()
                (activity as? MainActivity)?.showMessage(
                    getString(R.string.update_download_failed),
                )
            } finally {
                downloadingUpdate = false
                updateUpdateButton()
            }
        }
    }

    private fun updateUpdateButton() {
        binding?.checkUpdateButton?.apply {
            isEnabled = !checkingUpdate && !downloadingUpdate
            setText(
                if (checkingUpdate) R.string.checking_update else R.string.check_update,
            )
        }
    }

    private fun openGitHubRepository() {
        val opened = runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.github_repository_url)),
                ),
            )
        }.isSuccess
        if (!opened) {
            (activity as? MainActivity)?.showMessage(
                getString(R.string.open_link_failed),
            )
        }
    }

    private fun showFirstWeekDatePicker() {
        val state = repository().state.value
        val term = resolveTerm(state)
        val initial = state.firstWeekStarts[term.label] ?: term.startDate
        val dialog = DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val selected = LocalDate.of(year, month + 1, day)
                viewLifecycleOwner.lifecycleScope.launch {
                    repository().setFirstWeekStart(term, selected)
                    (activity as? MainActivity)?.showMessage(
                        getString(R.string.first_week_updated),
                    )
                }
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        )
        dialog.setButton(
            DialogInterface.BUTTON_NEUTRAL,
            getString(R.string.restore_auto),
        ) { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                repository().clearFirstWeekStart(term.label)
                (activity as? MainActivity)?.showMessage(
                    getString(R.string.first_week_restored),
                )
            }
        }
        dialog.show()
    }

    private fun resolveTerm(state: AppState): Term =
        Term.parse(state.currentTerm)
            ?: Term.parse(state.selectedTerm)
            ?: state.activeSchedule?.term
            ?: Term.guess()

    private fun showReminderDialog() {
        val values = intArrayOf(0, 5, 10, 15, 30)
        val labels = arrayOf(
            getString(R.string.reminder_off),
            getString(R.string.reminder_5),
            getString(R.string.reminder_10),
            getString(R.string.reminder_15),
            getString(R.string.reminder_30),
        )
        val settings = (requireActivity().application as CourseApplication).container.settings
        val selected = values.indexOf(settings.reminderMinutes).takeIf { it >= 0 } ?: 3
        var choice = selected
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reminder_title)
            .setSingleChoiceItems(labels, selected) { _, which -> choice = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                settings.reminderMinutes = values[choice]
                settings.remindedKeys = emptySet()
            }
            .show()
    }

    private fun syncAll() {
        if (syncing) return
        val repository = repository()
        if (!repository.hasSession) {
            (activity as? MainActivity)?.showLogin()
            return
        }
        syncing = true
        binding?.syncButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { repository.syncAll(forceCalendar = false) }
                .onSuccess { (activity as? MainActivity)?.showMessage(it.message) }
                .onFailure { (activity as? MainActivity)?.handleSyncError(it) }
            syncing = false
            binding?.syncButton?.isEnabled = true
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository().logout(clearData = false)
                    binding?.accountStatus?.setText(R.string.more_disconnected)
                    (activity as? MainActivity)?.showMessage(getString(R.string.more_disconnected))
                }
            }
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository().logout(clearData = true)
                    (activity as? MainActivity)?.showLogin()
                }
            }
            .show()
    }

    private fun repository() =
        (requireActivity().application as CourseApplication).container.repository

    private fun requireApp(): CourseApplication =
        requireActivity().application as CourseApplication

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d")
    }
}
