package com.example.ui.util

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Manager for rich haptic feedback for key user interactions:
 * - Device Discovered (subtle tactile tick)
 * - Transfer Start (affirming double-pulse)
 * - Transfer Completion (triumphant rhythm pulse)
 * - Transfer Error / Cancellation (warning buzz)
 * - QR Scan / Button Tap (crisp click)
 */
class HapticFeedbackHelper(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire Vibrator service", e)
            null
        }
    }

    /**
     * Subtle, light tactile tick when a new device is discovered nearby.
     */
    fun performDeviceDiscovered() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(20L, 90))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(20L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic trigger ignored", e)
        }
    }

    /**
     * Affirming double-pulse when connection handshake succeeds and file transfer starts.
     */
    fun performTransferStart() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Pattern: [delay, on1, off, on2]
                val timings = longArrayOf(0, 35, 60, 50)
                val amplitudes = intArrayOf(0, 160, 0, 220)
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 35, 60, 50), -1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic trigger ignored", e)
        }
    }

    /**
     * Dynamic success rhythm pattern when all files finish transferring successfully.
     */
    fun performTransferComplete() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Success celebration rhythm: short tap, quick rest, energetic tap, medium tap
                val timings = longArrayOf(0, 40, 50, 40, 50, 90)
                val amplitudes = intArrayOf(0, 140, 0, 180, 0, 255)
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 40, 50, 40, 50, 90), -1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic trigger ignored", e)
        }
    }

    /**
     * Warning buzz on transfer cancellation or transfer failure.
     */
    fun performTransferFailed() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 70, 70, 90)
                val amplitudes = intArrayOf(0, 200, 0, 230)
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 70, 70, 90), -1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic trigger ignored", e)
        }
    }

    /**
     * Crisp interaction click when user taps a primary action or scans a QR code.
     */
    fun performClick() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(25L, 120))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(25L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Haptic trigger ignored", e)
        }
    }

    companion object {
        private const val TAG = "HapticFeedbackHelper"

        @Volatile
        private var instance: HapticFeedbackHelper? = null

        fun getInstance(context: Context): HapticFeedbackHelper {
            return instance ?: synchronized(this) {
                instance ?: HapticFeedbackHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
