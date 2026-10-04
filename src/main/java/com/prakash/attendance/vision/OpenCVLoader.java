package com.prakash.attendance.vision;

import org.opencv.core.Core;

/**
 * OpenCV is written in C++. The Java classes (Mat, CascadeClassifier...) are thin wrappers
 * that call into a native library (.dll / .so / .dylib) through JNI. That native library
 * must be loaded once, before any OpenCV class is used.
 */
public final class OpenCVLoader {

    private static boolean loaded = false;

    private OpenCVLoader() {}

    public static synchronized void load() {
        if (loaded) return;
        try {
            // The org.openpnp:opencv jar bundles natives for every OS and
            // extracts the right one. Called via reflection so this class still
            // compiles if you swap in a different OpenCV jar.
            Class.forName("nu.pattern.OpenCV").getMethod("loadLocally").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            // Fallback: an OpenCV you installed yourself, found on java.library.path.
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        }
        loaded = true;
        System.out.println("OpenCV loaded: " + Core.VERSION);
    }
}
