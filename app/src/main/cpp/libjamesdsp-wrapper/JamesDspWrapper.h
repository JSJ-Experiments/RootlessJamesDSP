#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>
#include <mutex>
#include <vector>

namespace fieldsurround {
class FieldSurroundProcessor;
}

typedef struct
{
    void* dsp;
    fieldsurround::FieldSurroundProcessor* fieldSurround;
    JNIEnv* env;
    jobject callbackInterface;
    jmethodID callbackOnLiveprogOutput;
    jmethodID callbackOnLiveprogExec;
    jmethodID callbackOnLiveprogResult;
    jmethodID callbackOnVdcParseError;
    std::mutex tempBufferMutex;
    std::vector<float> tempBuffer;
} JamesDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
