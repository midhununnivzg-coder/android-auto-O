package org.androidauto.headunit;

import android.os.ParcelFileDescriptor;

/** JNI bridge into the Rust Android Auto protocol engine. */
final class NativeProjection {
    static {
        System.loadLibrary("android_auto");
    }

    private NativeProjection() {}

    static native long nativeStart(MainActivity activity, ParcelFileDescriptor socket,
                                   int videoWidth, int videoHeight);
    static native void nativeTouch(long handle, int x, int y, int action, int pointerId);
    static native void nativeStop(long handle);
}
