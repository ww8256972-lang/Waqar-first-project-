#include <jni.h>
#include <sys/prctl.h>
#include <sys/resource.h>

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_waqar_institute_sandbox_MicroVmExamSandboxManager_nativeEnterMicroVmExamSandbox(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    // 1. Block privilege escalation inside the exam sandbox execution thread
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    // 2. Block memory core dumps and ptrace attachment to the exam memory space
    prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    // 3. Enforce zero core-file size limit (RLIMIT_CORE = 0)
    struct rlimit coreLimit{0, 0};
    setrlimit(RLIMIT_CORE, &coreLimit);
    return JNI_TRUE;
}
