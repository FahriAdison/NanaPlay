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
#include <ctime>

#include <unwind.h>
#include <dlfcn.h>

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
// NOTE: this handler is best-effort, NOT strictly async-signal-safe. snprintf,
// _Unwind_Backtrace and dladdr are not in the POSIX async-signal-safe list.
// chaining guarantees termination only if this handler returns.
static char g_crashFilePath[4096] = {0};
static int g_versionCode = 0;

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

    time_t now = time(nullptr);
    char header[384];
    int hlen = snprintf(header, sizeof(header),
                        "NanaPlay native crash report\ntime=%lld\nversionCode=%d\nsignal=%s (%d)\nfaultAddr=%p\n",
                        (long long)now, g_versionCode, sigName, sig, faultAddr);
    if (hlen > (int)sizeof(header) - 1) hlen = (int)sizeof(header) - 1;
    if (hlen > 0) {
        // Best-effort write; ignore partial writes in a signal handler.
        (void)write(fd, header, (size_t)hlen);
    }

    const char *btHeader = "backtrace:\n";
    (void)write(fd, btHeader, 11);

    // Runtime unwind via the runtime unwind library's _Unwind_Backtrace. dladdr()
    // resolves each PC to symbol name + offset + module so the result can be
    // symbolized offline (addr2line/llvm-symbolizer) even without debug info.
    struct BacktraceState {
        void **frames;
        int frameCount;
        int maxFrames;
    };
    BacktraceState state;
    void *frames[64];
    state.frames = frames;
    state.frameCount = 0;
    state.maxFrames = 64;

    // _Unwind_Backtrace is provided by the runtime unwind library linked into
    // every native process.
    _Unwind_Backtrace(
        [](_Unwind_Context *ctx, void *arg) -> _Unwind_Reason_Code {
            BacktraceState *s = (BacktraceState *)arg;
            if (s->frameCount >= s->maxFrames) return _URC_END_OF_STACK;
            void *pc = (void *)_Unwind_GetIP(ctx);
            if (pc != nullptr) s->frames[s->frameCount++] = pc;
            return _URC_NO_REASON;
        },
        &state);

    char line[512];
    for (int i = 0; i < state.frameCount; i++) {
        void *pc = frames[i];
        Dl_info info;
        if (dladdr(pc, &info) != 0 && info.dli_fname != nullptr) {
            ptrdiff_t offset;
            if (info.dli_saddr != nullptr) {
                offset = (const char *)pc - (const char *)info.dli_saddr;
            } else {
                offset = (const char *)pc - (const char *)info.dli_fbase;
            }
            const char *sym = (info.dli_sname != nullptr) ? info.dli_sname : "??";
            int len = snprintf(line, sizeof(line), "#%02d %p %s+%td (%s)\n",
                               i, pc, sym, offset, info.dli_fname);
            if (len > (int)sizeof(line) - 1) len = (int)sizeof(line) - 1;
            if (len > 0) (void)write(fd, line, (size_t)len);
        } else {
            int len = snprintf(line, sizeof(line), "#%02d %p\n", i, pc);
            if (len > (int)sizeof(line) - 1) len = (int)sizeof(line) - 1;
            if (len > 0) (void)write(fd, line, (size_t)len);
        }
    }

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
Java_com_opencloudgaming_opennow_NativeCrashReporter_installNativeCrashHandler(JNIEnv *env, jobject, jstring path, jint versionCode) {
    g_versionCode = (int)versionCode;
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
