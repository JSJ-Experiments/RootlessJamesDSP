#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>
#include <mutex>
#include <vector>

namespace fieldsurround {
class FieldSurroundProcessor;
}

namespace clarity {
class ClarityProcessor;
}

typedef struct
{
    void* dsp;
    fieldsurround::FieldSurroundProcessor* fieldSurround;
    clarity::ClarityProcessor* clarity;
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
