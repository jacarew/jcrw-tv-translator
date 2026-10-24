// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.res.Resources
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivitySettingsBinding

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
{
	private lateinit var binding: ActivitySettingsBinding
	private var controllerCaptureCallback: ((Int) -> Unit)? = null
	private var capturedKeyCodeToSuppress: Int? = null

	fun captureNextControllerButton(callback: (Int) -> Unit)
	{
		controllerCaptureCallback = callback
	}

	fun cancelControllerButtonCapture()
	{
		controllerCaptureCallback = null
	}

	private fun isControllerButton(event: KeyEvent): Boolean
	{
		val code = event.keyCode
		val source = event.source or (event.device?.sources ?: 0)
		val isGameController = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
			(source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
		// TV vendor key layouts can report face buttons under nonstandard codes.
		// During capture accept any key from the gamepad device, including BACK.
		return isGameController || code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
			code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ||
			code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT
	}

	fun captureControllerEvent(event: KeyEvent): Boolean
	{
		val callback = controllerCaptureCallback ?: return false
		if(capturedKeyCodeToSuppress == event.keyCode)
		{
			if(event.action == KeyEvent.ACTION_UP) capturedKeyCodeToSuppress = null
			return true
		}
		if(!isControllerButton(event)) return false
		if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
		{
			controllerCaptureCallback = null
			capturedKeyCodeToSuppress = event.keyCode
			callback(event.keyCode)
		}
		return true
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(captureControllerEvent(event)) return true
		return super.dispatchKeyEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		val callback = controllerCaptureCallback
		val isControllerMotion = (event.source and InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK ||
			(event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
		if(callback != null && isControllerMotion)
		{
			val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			// Many Bluetooth controllers expose L2/R2 as analog axes instead of
			// KeyEvents, so listen for a real trigger press during mapping too.
			val leftTrigger = maxOf(
				event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
				event.getAxisValue(MotionEvent.AXIS_BRAKE))
			val rightTrigger = maxOf(
				event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
				event.getAxisValue(MotionEvent.AXIS_GAS))
			val keyCode = when {
				leftTrigger > 0.5f -> KeyEvent.KEYCODE_BUTTON_L2
				rightTrigger > 0.5f -> KeyEvent.KEYCODE_BUTTON_R2
				x < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
				x > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
				y < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
				y > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
				else -> null
			}
			if(keyCode != null)
			{
				controllerCaptureCallback = null
				callback(keyCode)
				return true
			}
		}
		return super.onGenericMotionEvent(event)
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		title = ""
		setSupportActionBar(binding.toolbar)

		val rootFragment = SettingsFragment()
		replaceFragment(rootFragment, false)
		supportFragmentManager.addOnBackStackChangedListener {
			val titleFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment ?: return@addOnBackStackChangedListener
			binding.titleTextView.text = titleFragment.getTitle(resources)
		}
		binding.titleTextView.text = rootFragment.getTitle(resources)
	}

	override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference) = when(pref.fragment)
	{
		SettingsRegisteredHostsFragment::class.java.canonicalName -> {
			replaceFragment(SettingsRegisteredHostsFragment(), true)
			true
		}
		else -> false
	}

	private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean)
	{
		supportFragmentManager.beginTransaction()
			.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
			.replace(R.id.settingsFragment, fragment)
			.also {
				if(addToBackStack)
					it.addToBackStack(null)
			}
			.commit()
	}
}
