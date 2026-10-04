package com.prakash.attendance.vision;

/**
 * A plain-Java grayscale image: width x height pixels, each 0 (black) to 255 (white),
 * stored row by row. The recognizer works on this instead of OpenCV's Mat, so the
 * algorithm is pure Java you can read line by line and test without a camera.
 */
public record GrayImage(int width, int height, int[] pixels) {

    public GrayImage {
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("pixels must have width*height entries");
        }
    }

    /** Pixel value at column x, row y. */
    public int at(int x, int y) {
        return pixels[y * width + x];
    }
}
