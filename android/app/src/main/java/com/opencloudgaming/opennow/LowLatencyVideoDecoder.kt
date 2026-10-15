package com.opencloudgaming.opennow

import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

class LowLatencyVideoDecoder(
    private val delegate: VideoDecoder,
    private val requestedFps: Int,
    private val lowLatencyEnabled: Boolean,
) : VideoDecoder {

    @Volatile private var patched = false

    override fun initDecode(settings: VideoDecoder.Settings?, decodeCallback: VideoDecoder.Callback?): VideoCodecStatus {
        NativeInputDiagnostics.add(
            "MediaCodecVideoDecoder initDecode delegate=${delegate.javaClass.name} " +
                "requestedFps=$requestedFps lowLatency=$lowLatencyEnabled",
        )
        patchMediaCodecWrapperFactory()
        return delegate.initDecode(settings, decodeCallback)
    }

    override fun release(): VideoCodecStatus {
        return delegate.release()
    }

    override fun decode(frame: EncodedImage?, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        return delegate.decode(frame, info)
    }

    override fun getImplementationName(): String {
        val suffix = if (lowLatencyEnabled) "low-latency" else "performance"
        return delegate.implementationName + "+opennow-$suffix"
    }

    private fun patchMediaCodecWrapperFactory() {
        if (patched) {
            return
        }

        try {
            val factoryField = findMediaCodecWrapperFactoryField(delegate.javaClass)
            if (factoryField == null) {
                val msg = "MediaCodecWrapperFactory field not found on ${delegate.javaClass.name}"
                Log.w(TAG, msg)
                NativeInputDiagnostics.add("LowLatencyVideoDecoder: $msg")
                // Terminal for this delegate class: retrying won't help.
                patched = true
                return
            }

            factoryField.isAccessible = true
            val originalFactory = factoryField.get(delegate)
            if (originalFactory == null) {
                val msg = "MediaCodecWrapperFactory is null on ${delegate.javaClass.name}"
                Log.w(TAG, msg)
                NativeInputDiagnostics.add("LowLatencyVideoDecoder: $msg")
                // Terminal for this instance: retrying won't help.
                patched = true
                return
            }

            val factoryInterface = factoryField.type
            val proxyFactory = Proxy.newProxyInstance(
                factoryInterface.classLoader,
                arrayOf(factoryInterface),
                MediaCodecWrapperFactoryHandler(
                    delegateFactory = originalFactory,
                    requestedFps = requestedFps,
                    lowLatencyEnabled = lowLatencyEnabled,
                )
            )
            factoryField.set(delegate, proxyFactory)
            // Only mark patched after the patch actually landed, so a
            // transient reflection failure can be retried on the next init.
            patched = true
            val msg = "Successfully patched MediaCodecWrapperFactory on ${delegate.javaClass.name}"
            Log.i(TAG, msg)
            NativeInputDiagnostics.add("LowLatencyVideoDecoder: $msg")
        } catch (tr: Throwable) {
            val msg = "Failed to install low latency MediaCodec wrapper: ${tr.message}"
            Log.w(TAG, msg, tr)
            NativeInputDiagnostics.add("LowLatencyVideoDecoder: $msg")
        }
    }

    private fun findMediaCodecWrapperFactoryField(clazz: Class<*>?): Field? {
        var current = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                if ("org.webrtc.MediaCodecWrapperFactory" == field.type.name ||
                    field.name.lowercase(Locale.US).contains("mediacodecwrapperfactory")
                ) {
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    private class MediaCodecWrapperFactoryHandler(
        private val delegateFactory: Any,
        private val requestedFps: Int,
        private val lowLatencyEnabled: Boolean,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val originalCodecName = if ("createByCodecName" == method.name && args != null && args.isNotEmpty() && args[0] is String) {
                args[0] as String
            } else {
                ""
            }

            val modifiedCodecName = if (lowLatencyEnabled && originalCodecName.isNotEmpty()) {
                getLowLatencyCodecNameIfApplicable(originalCodecName)
            } else {
                originalCodecName
            }

            val finalArgs = if (modifiedCodecName != originalCodecName && args != null) {
                Array<Any>(args.size) { i ->
                    if (i == 0) modifiedCodecName else args[i]
                }
            } else {
                args
            }

            val result = invokeDelegate(delegateFactory, method, finalArgs)
            if ("createByCodecName" != method.name || result == null) {
                return result
            }

            val codecName = modifiedCodecName
            NativeInputDiagnostics.add("LowLatencyVideoDecoder: createByCodecName called for codecName=$codecName")

            var codecWrapperInterface: Class<*>? = if (result.javaClass.interfaces.isNotEmpty()) {
                result.javaClass.interfaces[0]
            } else {
                null
            }

            if (codecWrapperInterface == null || "org.webrtc.MediaCodecWrapper" != codecWrapperInterface.name) {
                codecWrapperInterface = findInterface(result.javaClass, "org.webrtc.MediaCodecWrapper")
            }

            if (codecWrapperInterface == null) {
                NativeInputDiagnostics.add("LowLatencyVideoDecoder: MediaCodecWrapper interface not found on ${result.javaClass.name}")
                return result
            }

            NativeInputDiagnostics.add("LowLatencyVideoDecoder: Successfully wrapping MediaCodecWrapper of class ${result.javaClass.name}")
            // Pre-resolve the two intercepted methods so the per-frame hot path
            // can match by identity instead of by name on every call. If
            // resolution fails (unexpected interface shape), the handler falls
            // back to name matching — tuning keeps working either way.
            val configureMethod = runCatching {
                codecWrapperInterface.getMethod(
                    "configure",
                    MediaFormat::class.java,
                    Surface::class.java,
                    MediaCrypto::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            val startMethod = runCatching {
                codecWrapperInterface.getMethod("start")
            }.getOrNull()
            return Proxy.newProxyInstance(
                codecWrapperInterface.classLoader,
                arrayOf(codecWrapperInterface),
                MediaCodecWrapperHandler(
                    delegateCodec = result,
                    codecName = codecName,
                    requestedFps = requestedFps,
                    lowLatencyEnabled = lowLatencyEnabled,
                    configureMethod = configureMethod,
                    startMethod = startMethod,
                )
            )
        }
    }

    private class MediaCodecWrapperHandler(
        private val delegateCodec: Any,
        private val codecName: String,
        private val requestedFps: Int,
        private val lowLatencyEnabled: Boolean,
        private val configureMethod: Method?,
        private val startMethod: Method?,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            // Only configure/start are ever intercepted; everything else is a
            // straight forward. Identity match against the pre-resolved
            // interface methods (name match as fallback).
            if (isTargetMethod(method, configureMethod, "configure") &&
                args != null && args.isNotEmpty() && args[0] is MediaFormat
            ) {
                val format = args[0] as MediaFormat
                NativeInputDiagnostics.add(
                    "MediaCodecVideoDecoder: configure codec=$codecName requestedFps=$requestedFps " +
                        "lowLatency=$lowLatencyEnabled before=$format",
                )
                applyDecoderPerformanceFormat(format, requestedFps, lowLatencyEnabled)
                if (lowLatencyEnabled) applyLowLatencyFormat(format, codecName, requestedFps)
                NativeInputDiagnostics.add("MediaCodecVideoDecoder: configured format=$format")
            }
            val result = invokeDelegate(delegateCodec, method, args)
            if (isTargetMethod(method, startMethod, "start") && lowLatencyEnabled) {
                NativeInputDiagnostics.add("LowLatencyVideoDecoder: Intercepted start() for codec=$codecName")
                applyLowLatencyParameters(delegateCodec, codecName)
            }
            return result
        }

        private fun isTargetMethod(method: Method, resolved: Method?, name: String): Boolean =
            if (resolved != null) method == resolved else method.name == name
    }

    companion object {
        private const val TAG = "LowLatencyDecoder"

        /**
         * Cache for [isCodecSupported]: enumerating MediaCodecList involves
         * binder IPC and can take >1s on low-end devices. It used to run on
         * the decoder thread in the critical path to the first frame.
         */
        private val codecSupportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private fun isCodecSupportedCached(name: String): Boolean =
            codecSupportCache.getOrPut(name) { isCodecSupported(name) }

        private fun applyDecoderPerformanceFormat(
            format: MediaFormat,
            requestedFps: Int,
            lowLatencyEnabled: Boolean,
        ) {
            val exactTargetFps = mediaCodecPerformanceTargetFps(requestedFps)
            if (exactTargetFps != null) {
                putInt(format, MediaFormat.KEY_FRAME_RATE, exactTargetFps)
            }
            // F-19: skip when low-latency is on — applyLowLatencyFormat sets
            // priority/operating-rate itself; keeping both was a duplicate.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && exactTargetFps != null && !lowLatencyEnabled) {
                putInt(format, MediaFormat.KEY_PRIORITY, 0)
                putInt(format, MediaFormat.KEY_OPERATING_RATE, exactTargetFps)
            }
        }

        private fun findInterface(clazz: Class<*>?, interfaceName: String): Class<*>? {
            var current = clazz
            while (current != null) {
                for (item in current.interfaces) {
                    if (interfaceName == item.name) {
                        return item
                    }
                }
                current = current.superclass
            }
            return null
        }

        private fun invokeDelegate(target: Any, method: Method, args: Array<out Any>?): Any? {
            return try {
                method.isAccessible = true
                if (args == null) {
                    method.invoke(target)
                } else {
                    method.invoke(target, *args)
                }
            } catch (ex: InvocationTargetException) {
                throw ex.cause ?: ex
            } catch (ex: SecurityException) {
                if (args == null) {
                    method.invoke(target)
                } else {
                    method.invoke(target, *args)
                }
            }
        }

        private fun applyLowLatencyFormat(format: MediaFormat, codecName: String, requestedFps: Int) {
            putInt(format, "low-latency", 1)

            val normalizedCodecName = codecName.lowercase(Locale.US)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putInt(format, "priority", 0)
                // Argon-2: cap operating-rate to a sane FPS for ALL decoders. 0x7FFF
                // (32767) forces budget chipsets (e.g. Unisoc) to maximum clock,
                // causing overheat and video stutter on low-end devices.
                val operatingRate = requestedFps.coerceIn(60, 120)
                putInt(format, "operating-rate", operatingRate)
            }
            putInt(format, "allow-frame-drop", 1)
            putInt(format, "vdec-lowlatency", 1)
            putInt(format, "vendor.low-latency.enable", 1)

            if (isQualcommDecoder(normalizedCodecName)) {
                putInt(format, "vendor.qti-ext-dec-picture-order.enable", 1)
                putInt(format, "vendor.qti-ext-dec-low-latency.enable", 1)
                putInt(format, "vendor.rtc-ext-dec-low-latency.enable", 1)
            }

            if (isHiSiliconDecoder(normalizedCodecName)) {
                putInt(format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                putInt(format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
            }

            if (isMediaTekDecoder(normalizedCodecName)) {
                putInt(format, "vendor.mtk-dec-low-latency", 1)
                putInt(format, "vendor.mtk-dec-lowlatency", 1)
                putInt(format, "vendor.mtk-ext-dec-low-latency.enable", 1)
                putInt(format, "vendor.mtk-ext-dec-lowlatency.enable", 1)
                putInt(format, "vendor.mtk-vdec-lowlatency", 1)
                putInt(format, "vendor.mtk-vdec-low-latency", 1)
                putInt(format, "vendor.mtk.vdec.lowlatency", 1)
                putInt(format, "vendor.mtk.vdec.low-latency", 1)
                putInt(format, "vendor.mtk.dec.lowlatency", 1)
                putInt(format, "vendor.mtk.dec.low-latency", 1)
                putInt(format, "vendor.mtk.ext.dec.lowlatency.enable", 1)
            }

            Log.i(TAG, "Applied low latency decoder format for codec=$codecName")
        }

        private fun isQualcommDecoder(codecName: String): Boolean {
            return codecName.contains("qcom") || codecName.contains("qti")
        }

        private fun isHiSiliconDecoder(codecName: String): Boolean {
            val hardware = (Build.HARDWARE ?: "").lowercase(Locale.US)
            val board = (Build.BOARD ?: "").lowercase(Locale.US)
            val manufacturer = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
            return codecName.contains("hisi") ||
                    codecName.contains("kirin") ||
                    hardware.contains("hisi") ||
                    hardware.contains("kirin") ||
                    board.contains("hisi") ||
                    board.contains("kirin") ||
                    manufacturer.contains("huawei")
        }

        private fun isMediaTekDecoder(codecName: String): Boolean {
            val hardware = (Build.HARDWARE ?: "").lowercase(Locale.US)
            val board = (Build.BOARD ?: "").lowercase(Locale.US)
            val manufacturer = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
            return codecName.contains("mtk") ||
                    codecName.contains("mediatek") ||
                    hardware.contains("mtk") ||
                    hardware.contains("mediatek") ||
                    board.contains("mtk") ||
                    board.contains("mediatek") ||
                    manufacturer.contains("mediatek")
        }

        private fun getLowLatencyCodecNameIfApplicable(codecName: String): String {
            val normalized = codecName.lowercase(Locale.US)
            if (normalized.startsWith("c2.mtk.") && normalized.endsWith(".decoder")) {
                val lowLatencyName = "$codecName.lowlatency"
                if (isCodecSupportedCached(lowLatencyName)) {
                    Log.i(TAG, "LowLatencyVideoDecoder: Found MediaTek low latency variant: $lowLatencyName")
                    return lowLatencyName
                }
            }
            return codecName
        }

        private fun isCodecSupported(name: String): Boolean {
            try {
                val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                for (info in list.codecInfos) {
                    if (info.name.equals(name, ignoreCase = true)) {
                        return true
                    }
                }
            } catch (tr: Throwable) {
                Log.w(TAG, "Failed to check if codec is supported", tr)
            }
            return false
        }

        private fun putInt(format: MediaFormat, key: String, value: Int) {
            try {
                format.setInteger(key, value)
            } catch (tr: Throwable) {
                Log.w(TAG, "Failed to set MediaFormat key $key", tr)
            }
        }

        private fun applyLowLatencyParameters(delegateCodec: Any, codecName: String) {
            try {
                val field = findMediaCodecField(delegateCodec.javaClass) ?: return
                field.isAccessible = true
                val mediaCodec = field.get(delegateCodec) as? android.media.MediaCodec ?: return

                val bundle = android.os.Bundle()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bundle.putInt(android.media.MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1)
                }
                // Vendor MTK keys are only meaningful on MediaTek decoders;
                // don't spray them at Qualcomm/Exynos/HiSilicon codecs.
                if (isMediaTekDecoder(codecName.lowercase(Locale.US))) {
                    bundle.putInt("vendor.mtk-dec-low-latency", 1)
                    bundle.putInt("vendor.mtk-dec-lowlatency", 1)
                    bundle.putInt("vendor.mtk-ext-dec-low-latency.enable", 1)
                    bundle.putInt("vendor.mtk-ext-dec-lowlatency.enable", 1)
                    bundle.putInt("vendor.mtk-vdec-lowlatency", 1)
                    bundle.putInt("vendor.mtk-vdec-low-latency", 1)
                    bundle.putInt("vendor.mtk.vdec.lowlatency", 1)
                    bundle.putInt("vendor.mtk.vdec.low-latency", 1)
                    bundle.putInt("vendor.mtk.dec.lowlatency", 1)
                    bundle.putInt("vendor.mtk.dec.low-latency", 1)
                    bundle.putInt("vendor.mtk.ext.dec.lowlatency.enable", 1)
                }

                mediaCodec.setParameters(bundle)
                Log.i(TAG, "LowLatencyVideoDecoder: Successfully set MediaCodec parameters: $bundle")
                NativeInputDiagnostics.add("LowLatencyVideoDecoder: Successfully set MediaCodec parameters: $bundle")
            } catch (tr: Throwable) {
                Log.w(TAG, "Failed to apply dynamic MediaCodec parameters", tr)
                NativeInputDiagnostics.add("LowLatencyVideoDecoder: Failed to apply dynamic MediaCodec parameters: ${tr.message}")
            }
        }

        private fun findMediaCodecField(clazz: Class<*>?): Field? {
            var current = clazz
            while (current != null) {
                for (field in current.declaredFields) {
                    if (field.type == android.media.MediaCodec::class.java) {
                        return field
                    }
                }
                current = current.superclass
            }
            return null
        }
    }
}

/**
 * Tries a tuned [LowLatencyVideoDecoder] first; if its [initDecode] fails
 * (e.g. the device rejects the low-latency MediaFormat tuning in configure()),
 * releases it and retries with a freshly created untuned decoder, so video
 * doesn't die permanently on strict devices.
 *
 * WebRTC has no factory-level retry: a failed initDecode would otherwise
 * leave the video track dead for the whole session.
 */
internal class FallbackVideoDecoder(
    private val createPrimary: () -> VideoDecoder?,
    private val createFallback: () -> VideoDecoder?,
    private val codecLabel: String,
) : VideoDecoder {

    @Volatile
    private var active: VideoDecoder? = null

    @Volatile
    private var usingFallback = false

    override fun initDecode(settings: VideoDecoder.Settings?, decodeCallback: VideoDecoder.Callback?): VideoCodecStatus {
        val primary = createPrimary()
        if (primary != null) {
            val status = runCatching { primary.initDecode(settings, decodeCallback) }
                .getOrElse { VideoCodecStatus.ERROR }
            if (status == VideoCodecStatus.OK) {
                active = primary
                return VideoCodecStatus.OK
            }
            runCatching { primary.release() }
            Log.w(TAG, "Tuned decoder initDecode failed for $codecLabel; falling back to untuned decoder")
            NativeInputDiagnostics.add(
                "FallbackVideoDecoder: tuned initDecode failed for $codecLabel, using untuned fallback",
            )
        }
        val fallback = createFallback()
        if (fallback == null) {
            NativeInputDiagnostics.add("FallbackVideoDecoder: no fallback decoder available for $codecLabel")
            return VideoCodecStatus.ERROR
        }
        val status = runCatching { fallback.initDecode(settings, decodeCallback) }
            .getOrElse { VideoCodecStatus.ERROR }
        if (status == VideoCodecStatus.OK) {
            active = fallback
            usingFallback = true
        }
        return status
    }

    override fun release(): VideoCodecStatus {
        val decoder = active
        active = null
        return decoder?.release() ?: VideoCodecStatus.OK
    }

    override fun decode(frame: EncodedImage?, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        return active?.decode(frame, info) ?: VideoCodecStatus.ERROR
    }

    override fun getImplementationName(): String {
        val base = active?.implementationName ?: "fallback-pending"
        return if (usingFallback) "$base+untuned-fallback" else base
    }

    companion object {
        private const val TAG = "FallbackVideoDecoder"
    }
}

internal fun mediaCodecPerformanceTargetFps(requestedFps: Int): Int? =
    requestedFps.takeIf { it >= 60 }

internal fun shouldUseMediaCodecDecoderTuning(
    selectedDecoder: VideoDecoder?,
    approvedHardwareDecoder: VideoDecoder?,
    requestedFps: Int,
    lowLatencyEnabled: Boolean,
): Boolean =
    selectedDecoder != null &&
        selectedDecoder === approvedHardwareDecoder &&
        (lowLatencyEnabled || mediaCodecPerformanceTargetFps(requestedFps) != null)
