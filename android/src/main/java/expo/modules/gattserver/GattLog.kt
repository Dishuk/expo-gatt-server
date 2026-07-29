package expo.modules.gattserver

import android.util.Log

internal const val TAG = "ExpoGattServer"

/** Emits debug logs only when enabled via `adb shell setprop log.tag.ExpoGattServer DEBUG`. */
internal inline fun logDebug(message: () -> String) {
  if (Log.isLoggable(TAG, Log.DEBUG)) {
    Log.d(TAG, message())
  }
}
