package expo.modules.gattserver

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

// Worded to match what iOS reports for the same two `CBManagerState` values, since both platforms
// report them under the same `ERR_BLUETOOTH` code.
internal const val BLUETOOTH_UNSUPPORTED_MESSAGE = "BLE not supported on this device"
internal const val BLUETOOTH_OFF_MESSAGE = "Bluetooth is turned off"

/**
 * Maps a [BluetoothAdapter] state constant onto the platform-neutral state union shared with iOS. The
 * two transitional states are reported as `resetting` because the platform documents both as not yet
 * usable, which is exactly what `resetting` means to a consumer.
 */
fun normalizedBluetoothState(state: Int): String = when (state) {
  BluetoothAdapter.STATE_ON -> "poweredOn"
  BluetoothAdapter.STATE_OFF -> "poweredOff"
  BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF -> "resetting"
  else -> "unknown"
}

/** Reads the adapter state without needing a server. `getState()` requires no runtime permission. */
fun currentBluetoothState(context: Context): String {
  val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  val adapter = manager?.adapter ?: return "unsupported"
  return normalizedBluetoothState(adapter.state)
}

/**
 * The Bluetooth-level failure that stops anything from working, or `null` when the adapter is usable.
 * `isEnabled` is `@RequiresNoPermission`, so this is safe to call before any permission check.
 */
internal fun bluetoothUnavailable(adapter: BluetoothAdapter?): GattServerException? {
  if (adapter == null) {
    return GattServerException("ERR_BLUETOOTH", BLUETOOTH_UNSUPPORTED_MESSAGE)
  }
  if (!adapter.isEnabled) {
    return GattServerException("ERR_BLUETOOTH", BLUETOOTH_OFF_MESSAGE)
  }
  return null
}
