package one.mixin.android.ui.common.biometric

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.android.synthetic.main.fragment_transfer_bottom_sheet.view.*
import kotlinx.android.synthetic.main.layout_pin_biometric.view.*
import kotlinx.android.synthetic.main.view_round_title.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.mixin.android.Constants
import one.mixin.android.R
import one.mixin.android.api.MixinResponse
import one.mixin.android.extension.defaultSharedPreferences
import one.mixin.android.extension.notNullWithElse
import one.mixin.android.extension.putLong
import one.mixin.android.extension.toast
import one.mixin.android.extension.updatePinCheck
import one.mixin.android.ui.common.MixinBottomSheetDialogFragment
import one.mixin.android.util.BiometricUtil
import one.mixin.android.util.ErrorHandler
import one.mixin.android.util.getMixinErrorStringByCode

abstract class BiometricBottomSheetDialogFragment : MixinBottomSheetDialogFragment() {
    private var biometricDialog: BiometricDialog? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        contentView.title_view.right_iv.setOnClickListener { dismiss() }
        contentView.biometric_layout.setKeyboard(contentView.keyboard)
        contentView.biometric_layout.callback = biometricLayoutCallback
        contentView.post {
            contentView.biometric_layout.keyboardHeight = contentView.keyboard.height
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BiometricUtil.REQUEST_CODE_CREDENTIALS && resultCode == Activity.RESULT_OK) {
            showBiometricPrompt()
        }
    }

    abstract fun getBiometricInfo(): BiometricInfo

    abstract suspend fun invokeNetwork(pin: String): MixinResponse<*>

    abstract fun doWhenInvokeNetworkSuccess(response: MixinResponse<*>, pin: String)

    protected fun showErrorInfo(content: String, animate: Boolean = false) {
        if (!isAdded) return
        contentView.biometric_layout.showErrorInfo(content, animate)
    }

    private fun showBiometricPrompt() {
        biometricDialog = BiometricDialog(
            requireContext(),
            getBiometricInfo()
        )
        biometricDialog?.callback = biometricDialogCallback
        biometricDialog?.show()
    }

    private fun showPin() {
        if (!isAdded) return
        contentView.biometric_layout.showPin(true)
    }

    private fun onPinComplete(pin: String) = lifecycleScope.launch {
        if (!isAdded) return@launch

        contentView.biometric_layout.showPb()
        val response = try {
            withContext(Dispatchers.IO) {
                invokeNetwork(pin)
            }
        } catch (t: Throwable) {
            contentView.biometric_layout?.showPin(true)
            ErrorHandler.handleError(t)
            return@launch
        }

        if (response.isSuccess) {
            defaultSharedPreferences.putLong(
                Constants.BIOMETRIC_PIN_CHECK,
                System.currentTimeMillis()
            )
            context?.updatePinCheck()

            doWhenInvokeNetworkSuccess(response, pin)

            dismiss()
            callback.notNullWithElse({ action -> action.onSuccess() }, {
                toast(R.string.successful)
            })
        } else {
            contentView.biometric_layout?.let { layout ->
                layout.setErrorButton(response.errorCode)
                layout.pin.clear()
            }
            showErrorInfo(requireContext().getMixinErrorStringByCode(response.errorCode, response.errorDescription), true)
        }
    }

    private val biometricDialogCallback = object : BiometricDialog.Callback {
        override fun onPinComplete(pin: String) {
            this@BiometricBottomSheetDialogFragment.onPinComplete(pin)
        }

        override fun showPin() {
            contentView.biometric_layout?.showPin(false)
        }

        override fun showAuthenticationScreen() {
            BiometricUtil.showAuthenticationScreen(this@BiometricBottomSheetDialogFragment)
        }

        override fun onCancel() {
            context?.let {
                contentView.biometric_layout?.isBiometricTextVisible(BiometricUtil.shouldShowBiometric(it))
            }
        }
    }

    private val biometricLayoutCallback = object : BiometricLayout.Callback {
        override fun onPinComplete(pin: String) {
            this@BiometricBottomSheetDialogFragment.onPinComplete(pin)
        }

        override fun onShowBiometric() {
            showBiometricPrompt()
        }

        override fun onDismiss() {
            dismiss()
        }
    }

    var callback: Callback? = null

    interface Callback {
        fun onSuccess()
    }
}
