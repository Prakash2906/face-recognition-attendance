package com.prakash.attendance.vision;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

/** Thin wrapper around OpenCV's VideoCapture (the webcam). */
public class Camera implements AutoCloseable {

    private final VideoCapture capture;

    public Camera(int index, int width, int height) {
        capture = new VideoCapture(index);
        if (capture.isOpened()) {
            capture.set(Videoio.CAP_PROP_FRAME_WIDTH, width);
            capture.set(Videoio.CAP_PROP_FRAME_HEIGHT, height);
        }
    }

    public boolean isOpened() {
        return capture.isOpened();
    }

    /**
     * Grabs the next frame into {@code frame}, mirrored so it behaves like a mirror
     * (moving left moves left on screen). Returns false if no frame was available.
     */
    public boolean read(Mat frame) {
        if (!capture.read(frame) || frame.empty()) return false;
        Core.flip(frame, frame, 1); // 1 = flip around the vertical axis
        return true;
    }

    @Override
    public void close() {
        capture.release();
    }
}
