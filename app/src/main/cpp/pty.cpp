#include <jni.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <signal.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <cstdlib>
#include <mutex>
#include <poll.h>
#include <unordered_map>

namespace {
struct Session { int master; pid_t child; };
std::mutex sessionsMutex;
std::unordered_map<jlong, Session> sessions;
jlong nextHandle = 1;
Session findSession(jlong id) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto it = sessions.find(id);
    return it == sessions.end() ? Session{-1, -1} : it->second;
}
void throwIo(JNIEnv* env, const char* what) {
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) env->ThrowNew(cls, (std::string(what) + ": " + std::strerror(errno)).c_str());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lunixdev_puppyterminal_PtySession_nativeStart(JNIEnv* env, jobject, jstring rootfs, jstring proot, jstring loader, jstring prootTmp, jint rows, jint cols, jboolean shellIntegration) {
    const char* rootPath = env->GetStringUTFChars(rootfs, nullptr);
    const char* prootPath = env->GetStringUTFChars(proot, nullptr);
    const char* loaderPath = env->GetStringUTFChars(loader, nullptr);
    const char* tmpPath = env->GetStringUTFChars(prootTmp, nullptr);
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) {
        int saved = errno; if (master >= 0) close(master); errno = saved;
        env->ReleaseStringUTFChars(rootfs, rootPath); env->ReleaseStringUTFChars(proot, prootPath); env->ReleaseStringUTFChars(loader, loaderPath); env->ReleaseStringUTFChars(prootTmp, tmpPath);
        throwIo(env, "PTY allocation failed"); return -1;
    }
    char* slaveName = ptsname(master);
    if (!slaveName) { int saved=errno; close(master); errno=saved; env->ReleaseStringUTFChars(rootfs,rootPath); env->ReleaseStringUTFChars(proot,prootPath); env->ReleaseStringUTFChars(loader,loaderPath); env->ReleaseStringUTFChars(prootTmp,tmpPath); throwIo(env,"PTY slave lookup failed"); return -1; }
    winsize ws{}; ws.ws_row = static_cast<unsigned short>(rows); ws.ws_col = static_cast<unsigned short>(cols);
    ioctl(master, TIOCSWINSZ, &ws);
    pid_t child = fork();
    if (child < 0) { int saved=errno; close(master); errno=saved; env->ReleaseStringUTFChars(rootfs,rootPath); env->ReleaseStringUTFChars(proot,prootPath); env->ReleaseStringUTFChars(loader,loaderPath); env->ReleaseStringUTFChars(prootTmp,tmpPath); throwIo(env,"Linux process creation failed"); return -1; }
    if (child == 0) {
        if (setsid() < 0) { dprintf(STDERR_FILENO, "puppy: setsid failed: %s\n", std::strerror(errno)); _exit(126); }
        int slave = open(slaveName, O_RDWR);
        if (slave < 0) { dprintf(STDERR_FILENO, "puppy: opening PTY slave failed: %s\n", std::strerror(errno)); _exit(126); }
        if (ioctl(slave, TIOCSCTTY, 0) < 0) { dprintf(STDERR_FILENO, "puppy: setting PTY control terminal failed: %s\n", std::strerror(errno)); _exit(126); }
        dup2(slave, STDIN_FILENO); dup2(slave, STDOUT_FILENO); dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        if (chdir(rootPath) < 0) { dprintf(STDERR_FILENO, "puppy: entering Linux rootfs failed: %s\n", std::strerror(errno)); _exit(126); }
        char* const argv[] = {
            const_cast<char*>(prootPath), const_cast<char*>("-r"), const_cast<char*>(rootPath),
            const_cast<char*>("-b"), const_cast<char*>("/dev"),
            const_cast<char*>("-b"), const_cast<char*>("/proc"),
            const_cast<char*>("-b"), const_cast<char*>("/sys"),
            const_cast<char*>("-b"), const_cast<char*>("/system"),
            const_cast<char*>("--link2symlink"), const_cast<char*>("-0"), const_cast<char*>("-L"),
            const_cast<char*>("-w"), const_cast<char*>("/home/puppy"),
            const_cast<char*>("/bin/sh"), const_cast<char*>("-l"), nullptr
        };
        char homeEnv[] = "HOME=/home/puppy";
        char userEnv[] = "USER=puppy";
        char logNameEnv[] = "LOGNAME=puppy";
        char shellEnv[] = "SHELL=/bin/sh";
        char termEnv[] = "TERM=xterm-256color";
        char langEnv[] = "LANG=C.UTF-8";
        char pathEnv[] = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
        char tmpEnv[4096];
        std::snprintf(tmpEnv, sizeof(tmpEnv), "PROOT_TMP_DIR=%s", tmpPath);
        char loaderEnv[4096];
        std::snprintf(loaderEnv, sizeof(loaderEnv), "PROOT_LOADER=%s", loaderPath);
        char noSeccompEnv[] = "PROOT_NO_SECCOMP=1";
        char integrationEnv[] = "PUPPY_SHELL_INTEGRATION=0";
        char integrationEnabledEnv[] = "PUPPY_SHELL_INTEGRATION=1";
        char* const envp[] = {homeEnv,userEnv,logNameEnv,shellEnv,termEnv,langEnv,pathEnv,tmpEnv,loaderEnv,noSeccompEnv,shellIntegration ? integrationEnabledEnv : integrationEnv,nullptr};
        execve(prootPath, argv, envp);
        dprintf(STDERR_FILENO, "puppy: starting PRoot from %s failed: %s\n", prootPath, std::strerror(errno));
        _exit(127);
    }
    env->ReleaseStringUTFChars(rootfs, rootPath); env->ReleaseStringUTFChars(proot, prootPath); env->ReleaseStringUTFChars(loader, loaderPath); env->ReleaseStringUTFChars(prootTmp, tmpPath);
    fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);
    std::lock_guard<std::mutex> lock(sessionsMutex);
    const jlong id = nextHandle++;
    sessions.emplace(id, Session{master, child});
    return id;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lunixdev_puppyterminal_PtySession_nativeRead(JNIEnv* env, jobject, jlong id, jbyteArray target) {
    const Session session = findSession(id);
    if (session.master < 0) return -2;
    pollfd descriptor{session.master, POLLIN | POLLHUP | POLLERR, 0};
    int ready;
    do { ready = poll(&descriptor, 1, 100); } while (ready < 0 && errno == EINTR);
    if (ready == 0) return 0;
    if (ready < 0) { throwIo(env,"PTY poll failed"); return -1; }
    jsize size = env->GetArrayLength(target); jbyte* bytes = env->GetByteArrayElements(target, nullptr);
    ssize_t n = read(session.master, bytes, static_cast<size_t>(size)); int saved=errno;
    env->ReleaseByteArrayElements(target, bytes, n > 0 ? 0 : JNI_ABORT); errno=saved;
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) return 0;
    if (n == 0 || (n < 0 && errno == EIO)) return -2;
    if (n < 0) { throwIo(env,"PTY read failed"); return -1; }
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lunixdev_puppyterminal_PtySession_nativeWrite(JNIEnv* env, jobject, jlong id, jbyteArray source) {
    const Session session = findSession(id);
    if (session.master < 0) return -1;
    jsize size=env->GetArrayLength(source); jbyte* bytes=env->GetByteArrayElements(source,nullptr);
    ssize_t n=write(session.master,bytes,static_cast<size_t>(size)); int saved=errno;
    env->ReleaseByteArrayElements(source,bytes,JNI_ABORT); errno=saved;
    if (n < 0 && errno == EINTR) return 0;
    if (n < 0) { throwIo(env,"PTY write failed"); return -1; }
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lunixdev_puppyterminal_PtySession_nativeResize(JNIEnv*, jobject, jlong id, jint rows, jint cols) {
    const Session session = findSession(id); if (session.master < 0) return;
    winsize ws{}; ws.ws_row=static_cast<unsigned short>(rows); ws.ws_col=static_cast<unsigned short>(cols);
    ioctl(session.master,TIOCSWINSZ,&ws);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lunixdev_puppyterminal_PtySession_nativeClose(JNIEnv*, jobject, jlong id) {
    Session session;
    { std::lock_guard<std::mutex> lock(sessionsMutex); auto it=sessions.find(id); if(it==sessions.end()) return; session=it->second; sessions.erase(it); }
    close(session.master);
    if (session.child > 0) {
        kill(session.child,SIGHUP);
        int status=0;
        for (int i=0; i<20; ++i) {
            pid_t result=waitpid(session.child,&status,WNOHANG);
            if (result == session.child || (result < 0 && errno == ECHILD)) return;
            timespec pause{0,10*1000*1000}; nanosleep(&pause,nullptr);
        }
        kill(session.child,SIGKILL); waitpid(session.child,&status,0);
    }
}
