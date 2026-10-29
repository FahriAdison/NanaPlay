#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sstream>
#include <string>

#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <cstring>
#include <cstdio>

#include <android/api-level.h>

#if __ANDROID_API__ >= 33
#include <execinfo.h>
#define NANA_HAS_BACKTRACE 1
#else
#define NANA_HAS_BACKTRACE 0
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_opencloudgaming_opennow_NativeCodecProbe_nativeRuntimeSummary(JNIEnv *env, jobject) {
    std::ostringstream out;
    out << "{";
    out << "\"nativeLibrary\":\"opennow_native\",";
    out << "\"mediaNdk\":true,";
    out << "\"rtpPacketSize\":1140,";
    out << "\"inputProtocolVersion\":3";
    out << "}";
    const std::string value = out.str();
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opencloudgaming_opennow_NativeCodecProbe_nativeDecoderAvailable(JNIEnv *env, jobject, jstring mimeType) {
    if (mimeType == nullptr) {
        return JNI_FALSE;
    }

    const char *rawMimeType = env->GetStringUTFChars(mimeType, nullptr);
    if (rawMimeType == nullptr) {
        return JNI_FALSE;
    }

    AMediaCodec *codec = AMediaCodec_createDecoderByType(rawMimeType);
    env->ReleaseStringUTFChars(mimeType, rawMimeType);
    if (codec == nullptr) {
        return JNI_FALSE;
    }

    AMediaCodec_delete(codec);
    return JNI_TRUE;
}

// --- Native crash capture (minimal) ---
//
// Java's UncaughtExceptionHandler (CrashReporter) only fires for uncaught Java
// exceptions. A force close that comes from a native signal (SIGSEGV/SIGABRT/
// SIGILL/SIGFPE/SIGBUS) kills the process before any Java handler runs, which
// is exactly the silent "back to Home, no crash dialog" symptom seen in website
// client mode. This installs a POSIX signal handler that writes a minimal
// report to a file in the app's filesDir, then chains to the previously
// installed handler so the process still dies normally and the platform's own
// tombstone/debuggerd behaviour is preserved.
//
// NOTE: this handler is best-effort, NOT strictly async-signal-safe. snprintf
// and (where available) backtrace/backtrace_symbols_fd are not in the POSIX
// async-signal-safe list. Correct process termination is guaranteed by chaining
// to the old handler below, not by any async-signal-safety claim.
static char g_crashFilePath[4096] = {0};

static const int g_handledSignals[] = { SIGSEGV, SIGABRT, SIGILL, SIGFPE, SIGBUS };
static const int g_handledSignalCount = 5;
static struct sigaction g_oldActions[5];

static int signalIndex(int sig) {
    for (int i = 0; i < g_handledSignalCount; i++) {
        if (g_handledSignals[i] == sig) return i;
    }
    return -1;
}

static void writeNativeCrashReport(int sig, void *faultAddr) {
    if (g_crashFilePath[0] == '\0') return;
    int fd = open(g_crashFilePath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;

    const char *sigName = "UNKNOWN";
    switch (sig) {
        case SIGSEGV: sigName = "SIGSEGV"; break;
        case SIGABRT: sigName = "SIGABRT"; break;
        case SIGILL:  sigName = "SIGILL";  break;
        case SIGFPE:  sigName = "SIGFPE";  break;
        case SIGBUS:  sigName = "SIGBUS";  break;
    }

    char header[256];
    int hlen = snprintf(header, sizeof(header),
                        "NanaPlay native crash report\nsignal=%s (%d)\nfaultAddr=%p\n",
                        sigName, sig, faultAddr);
    if (hlen > (int)sizeof(header) - 1) hlen = (int)sizeof(header) - 1;
    if (hlen > 0) {
        // Best-effort write; ignore partial writes in a signal handler.
        (void)write(fd, header, (size_t)hlen);
    }

#if NANA_HAS_BACKTRACE
    void *frames[64];
    int frameCount = backtrace(frames, 64);
    const char *btHeader = "backtrace:\n";
    (void)write(fd, btHeader, 11);
    backtrace_symbols_fd(frames, frameCount, fd);
#else
    const char *note = "backtrace: compiled out at minSdk 23 build; platform tombstone carries the full backtrace\n";
    (void)write(fd, note, sizeof("backtrace: compiled out at minSdk 23 build; platform tombstone carries the full backtrace\n") - 1);
#endif

    close(fd);
}

static void nativeCrashSignalHandler(int sig, siginfo_t *info, void *ucontext) {
    void *faultAddr = (info != nullptr) ? info->si_addr : nullptr;
    writeNativeCrashReport(sig, faultAddr);
    // Restore the previously installed handler, then re-raise so the process
    // terminates normally (platform tombstone/debuggerd behaviour preserved).
    int idx = signalIndex(sig);
    if (idx >= 0) {
        sigaction(sig, &g_oldActions[idx], nullptr);
    }
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_opencloudgaming_opennow_NativeCrashReporter_installNativeCrashHandler(JNIEnv *env, jobject, jstring path) {
    if (path == nullptr) return;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return;
    strncpy(g_crashFilePath, p, sizeof(g_crashFilePath) - 1);
    g_crashFilePath[sizeof(g_crashFilePath) - 1] = '\0';
    env->ReleaseStringUTFChars(path, p);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = nativeCrashSignalHandler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);

    for (int i = 0; i < g_handledSignalCount; i++) {
        sigaction(g_handledSignals[i], &sa, &g_oldActions[i]);
    }
}
