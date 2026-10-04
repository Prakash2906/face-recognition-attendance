package com.prakash.attendance.util;

import com.prakash.attendance.vision.GrayImage;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/** Converts between OpenCV's Mat and the Java types the rest of the app uses. */
public final class ImageUtils {

    private ImageUtils() {}

    /** Mat (BGR or grayscale) to BufferedImage, so Swing can draw it. */
    public static BufferedImage toBufferedImage(Mat mat) {
        int type = mat.channels() == 1 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR;
        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
        // Copy the Mat's bytes straight into the image's backing array. OpenCV stores colour
        // as B,G,R which is exactly the byte order TYPE_3BYTE_BGR expects.
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.get(0, 0, target);
        return image;
    }

    /** 8-bit single-channel Mat to GrayImage (values 0..255) for the recognizer. */
    public static GrayImage toGrayImage(Mat gray) {
        if (gray.type() != CvType.CV_8UC1) {
            throw new IllegalArgumentException("Expected an 8-bit grayscale Mat");
        }
        int w = gray.cols(), h = gray.rows();
        byte[] bytes = new byte[w * h];
        gray.get(0, 0, bytes);
        int[] pixels = new int[w * h];
        // Java bytes are signed (-128..127); "& 0xFF" turns them back into 0..255.
        for (int i = 0; i < bytes.length; i++) pixels[i] = bytes[i] & 0xFF;
        return new GrayImage(w, h, pixels);
    }
}
