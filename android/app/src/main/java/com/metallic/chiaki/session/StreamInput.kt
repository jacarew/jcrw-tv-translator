package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ControllerButton
import com.metallic.chiaki.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		val customMask = preferences.controllerButtonMappings[event.keyCode]
		if(customMask != null)
		{
			val pressed = event.action == KeyEvent.ACTION_DOWN
			when(customMask)
			{
				ControllerButton.L2 -> keyControllerState.l2State = if(pressed) UByte.MAX_VALUE else 0U
				ControllerButton.R2 -> keyControllerState.r2State = if(pressed) UByte.MAX_VALUE else 0U
				else -> keyControllerState.buttons = keyControllerState.buttons.run {
					if(pressed) this or customMask.mask else this and customMask.mask.inv()
				}
			}
			controllerStateUpdated()
			return true
		}

		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
				keyControllerState.r2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
		}
		// Some Android TV firmware reports the DualSense Cross button as BACK.
		// Treat it as Cross only when it came from a game controller; the TV remote
		// Back key must keep its normal navigation behavior.
		val source = event.source or (event.device?.sources ?: 0)
		val gameController = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
			(source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
		if(event.keyCode == KeyEvent.KEYCODE_BACK && gameController)
		{
			val buttonMask = ControllerState.BUTTON_CROSS
			keyControllerState.buttons = keyControllerState.buttons.run {
				if(event.action == KeyEvent.ACTION_DOWN) this or buttonMask else this and buttonMask.inv()
			}
			controllerStateUpdated()
			return true
		}

		val buttonMask: UInt = when(event.keyCode)
		{
			// dpad handled by MotionEvents
			//KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
			//KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
			//KeyEvent.KEYCODE_DPAD_UP -> ControllerState.BUTTON_DPAD_UP
			//KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
			KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
			KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
			KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			else -> return false
		}

		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		return true
	}

	fun dispatchDpadKey(keyCode: Int, pressed: Boolean)
	{
		val mappedButton = preferences.controllerButtonMappings[keyCode]
		if(mappedButton == ControllerButton.L2) keyControllerState.l2State = if(pressed) UByte.MAX_VALUE else 0U
		else if(mappedButton == ControllerButton.R2) keyControllerState.r2State = if(pressed) UByte.MAX_VALUE else 0U
		else
		{
			val mask = mappedButton?.mask ?: when(keyCode)
			{
				KeyEvent.KEYCODE_DPAD_UP -> ControllerState.BUTTON_DPAD_UP
				KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
				else -> return
			}
			keyControllerState.buttons = keyControllerState.buttons.run {
				if(pressed) this or mask else this and mask.inv()
			}
		}
		controllerStateUpdated()
	}

	fun onGenericMotionEvent(event: MotionEvent, suppressDpadY: Boolean = false): Boolean
	{
		val isControllerMotion = event.source and InputDevice.SOURCE_CLASS_JOYSTICK == InputDevice.SOURCE_CLASS_JOYSTICK ||
			event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
		if(!isControllerMotion)
			return false
		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (coerceIn(0f, 1f) * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		motionControllerState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = event.getAxisValue(MotionEvent.AXIS_Z).signedAxis()
		motionControllerState.rightY = event.getAxisValue(MotionEvent.AXIS_RZ).signedAxis()
		val mappings = preferences.controllerButtonMappings
		val leftTrigger = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
		val rightTrigger = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
		val leftTriggerMapping = mappings[KeyEvent.KEYCODE_BUTTON_L2]
		val rightTriggerMapping = mappings[KeyEvent.KEYCODE_BUTTON_R2]
		val mappedTriggerButtons =
			(if(leftTrigger > 0.5f && leftTriggerMapping != null && leftTriggerMapping != ControllerButton.L2) leftTriggerMapping.mask else 0U) or
				(if(rightTrigger > 0.5f && rightTriggerMapping != null && rightTriggerMapping != ControllerButton.R2) rightTriggerMapping.mask else 0U)
		motionControllerState.l2State = if(leftTriggerMapping == null || leftTriggerMapping == ControllerButton.L2) leftTrigger.unsignedAxis() else 0U
		motionControllerState.r2State = if(rightTriggerMapping == null || rightTriggerMapping == ControllerButton.R2) rightTrigger.unsignedAxis() else 0U
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = if(suppressDpadY) 0f else event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) mappings[KeyEvent.KEYCODE_DPAD_RIGHT]?.mask ?: ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) mappings[KeyEvent.KEYCODE_DPAD_LEFT]?.mask ?: ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) mappings[KeyEvent.KEYCODE_DPAD_DOWN]?.mask ?: ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) mappings[KeyEvent.KEYCODE_DPAD_UP]?.mask ?: ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP or
					preferences.controllerButtonMappings.values.fold(0U) { mask, button -> mask or button.mask }).inv() or dpadButtons or mappedTriggerButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}
}
