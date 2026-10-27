package com.gzus.courseassistant.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.R
import com.gzus.courseassistant.data.network.SessionExpiredException
import com.gzus.courseassistant.databinding.ActivityMainBinding
import com.gzus.courseassistant.ui.academics.AcademicsFragment
import com.gzus.courseassistant.ui.login.LoginActivity
import com.gzus.courseassistant.ui.more.MoreFragment
import com.gzus.courseassistant.ui.timetable.TimetableFragment
import com.gzus.courseassistant.ui.today.TodayFragment
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: com.gzus.courseassistant.data.sync.AppRepository
    private var loginOpen = false

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        loginOpen = false
        if (result.resultCode == RESULT_OK) {
            binding.bottomNavigation.selectedItemId = R.id.nav_today
            result.data?.getStringExtra(EXTRA_LOGIN_MESSAGE)
                ?.takeIf { it.isNotBlank() }
                ?.let(::showMessage)
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showMessage(getString(R.string.notification_permission_hint))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiUtils.applySystemBarInsets(binding.root)
        repository = (application as CourseApplication).container.repository

        setupNavigation(savedInstanceState)
        requestNotificationPermission()
        if (intent.getBooleanExtra(EXTRA_OPEN_MORE, false)) {
            binding.bottomNavigation.selectedItemId = R.id.nav_more
        }
        if (!repository.hasSession && repository.state.value.currentSchedule == null) {
            showLogin()
            return
        }

        maybeRefresh()
        if (intent.getBooleanExtra(EXTRA_OPEN_TODAY, false)) {
            binding.bottomNavigation.selectedItemId = R.id.nav_today
        }
    }

    fun showLogin() {
        if (loginOpen) return
        loginOpen = true
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun handleSyncError(error: Throwable) {
        if (error is SessionExpiredException) {
            showMessage(getString(R.string.session_expired))
            showLogin()
        } else {
            showMessage(error.message ?: getString(R.string.unknown_error))
        }
    }

    private fun setupNavigation(savedInstanceState: Bundle?) {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_today -> TodayFragment()
                R.id.nav_timetable -> TimetableFragment()
                R.id.nav_academics -> AcademicsFragment()
                R.id.nav_more -> MoreFragment()
                else -> return@setOnItemSelectedListener false
            }
            replace(fragment, item.itemId.toString())
            true
        }
        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_today
        }
    }

    private fun replace(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.mainContainer, fragment, tag)
            .commit()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeRefresh() {
        if (!repository.hasSession) return
        val last = repository.state.value.lastScheduleSync
        val stale = System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(2)
        if (!stale) return
        lifecycleScope.launch {
            runCatching { repository.syncAll() }
                .onFailure { error ->
                    if (error !is SessionExpiredException) {
                        showMessage(error.message ?: getString(R.string.net_error))
                    }
                }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_TODAY, false)) {
            binding.bottomNavigation.selectedItemId = R.id.nav_today
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_MORE, false)) {
            binding.bottomNavigation.selectedItemId = R.id.nav_more
        }
    }

    companion object {
        const val EXTRA_OPEN_TODAY = "open_today"
        const val EXTRA_OPEN_MORE = "open_more"
        const val EXTRA_LOGIN_MESSAGE = "login_message"
    }
}
