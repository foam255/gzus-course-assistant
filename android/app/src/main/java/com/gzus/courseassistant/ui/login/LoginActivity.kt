package com.gzus.courseassistant.ui.login

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gzus.courseassistant.CourseApplication
import com.gzus.courseassistant.data.model.CaptchaChallenge
import com.gzus.courseassistant.data.network.CaptchaRejectedException
import com.gzus.courseassistant.data.network.JwxtException
import com.gzus.courseassistant.data.network.LoginRejectedException
import com.gzus.courseassistant.databinding.ActivityLoginBinding
import com.gzus.courseassistant.ui.main.MainActivity
import com.gzus.courseassistant.util.UiUtils
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var repository: com.gzus.courseassistant.data.sync.AppRepository
    private var captcha: CaptchaChallenge? = null
    private var loading = false
    private var captchaLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiUtils.applySystemBarInsets(binding.root, includeIme = true)
        repository = (application as CourseApplication).container.repository

        binding.loginButton.setOnClickListener { submit() }
        binding.captchaCard.setOnClickListener { loadCaptcha() }
        loadCaptcha()
    }

    private fun loadCaptcha() {
        if (loading || captchaLoading) return
        captchaLoading = true
        captcha = null
        binding.captchaPlaceholder.visibility = View.VISIBLE
        binding.captchaPlaceholder.text = getString(com.gzus.courseassistant.R.string.loading)
        binding.captchaImage.setImageDrawable(null)
        binding.captchaCard.isEnabled = false
        lifecycleScope.launch {
            try {
                val challenge = repository.fetchCaptcha()
                captcha = challenge
                val bytes = Base64.decode(
                    challenge.imageDataUri.substringAfter(","),
                    Base64.DEFAULT,
                )
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) throw JwxtException("验证码图片解析失败")
                binding.captchaImage.setImageBitmap(bitmap)
                binding.captchaPlaceholder.visibility = View.GONE
            } catch (error: Exception) {
                showError(error.message ?: getString(com.gzus.courseassistant.R.string.net_error))
                binding.captchaPlaceholder.text = getString(com.gzus.courseassistant.R.string.retry)
            } finally {
                captchaLoading = false
                binding.captchaCard.isEnabled = true
            }
        }
    }

    private fun submit() {
        if (loading) return
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val code = binding.captchaInput.text?.toString()?.trim().orEmpty()
        val challenge = captcha
        when {
            username.isBlank() -> {
                showError("请输入学号")
                return
            }
            password.isBlank() -> {
                showError("请输入密码")
                return
            }
            challenge == null -> {
                showError("验证码尚未加载")
                loadCaptcha()
                return
            }
            code.isBlank() -> {
                showError("请输入验证码")
                return
            }
        }

        hideKeyboard()
        setLoading(true)
        lifecycleScope.launch {
            var authenticated = false
            try {
                repository.login(
                    username = username,
                    password = password,
                    captchaId = challenge.id,
                    captchaCode = code,
                )
                authenticated = true
                binding.passwordInput.text?.clear()
                val summary = repository.syncAll()
                val message = summary.message.takeUnless { it == "同步完成" }
                setResult(
                    RESULT_OK,
                    Intent().putExtra(MainActivity.EXTRA_LOGIN_MESSAGE, message),
                )
                finish()
            } catch (error: CaptchaRejectedException) {
                showError(error.message.orEmpty())
                binding.captchaInput.text?.clear()
                captcha = null
                loadCaptcha()
            } catch (error: LoginRejectedException) {
                showError(error.message.orEmpty())
                binding.passwordInput.text?.clear()
                captcha = null
                loadCaptcha()
            } catch (error: Exception) {
                if (authenticated && repository.hasSession) {
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(
                            MainActivity.EXTRA_LOGIN_MESSAGE,
                            "登录成功，但同步失败：${error.message.orEmpty()}",
                        ),
                    )
                    finish()
                } else {
                    showError(
                        error.message
                            ?: getString(com.gzus.courseassistant.R.string.unknown_error),
                    )
                    if (authenticated || captcha == null) {
                        binding.passwordInput.text?.clear()
                        binding.captchaInput.text?.clear()
                        captcha = null
                        loadCaptcha()
                    }
                }
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(value: Boolean) {
        loading = value
        binding.loginButton.isEnabled = !value
        binding.loginProgress.visibility = if (value) View.VISIBLE else View.GONE
        binding.usernameInput.isEnabled = !value
        binding.passwordInput.isEnabled = !value
        binding.captchaInput.isEnabled = !value
        binding.loginButton.text = if (value) {
            getString(com.gzus.courseassistant.R.string.syncing)
        } else {
            getString(com.gzus.courseassistant.R.string.login)
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java)
        manager.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
