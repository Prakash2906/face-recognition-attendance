package com.prakash.attendance.vision;

import com.prakash.attendance.config.AppConfig;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds faces in a camera frame (detection) and turns each one into a clean,
 * standard-sized grayscale image ready for the recognizer (pre-processing).
 *
 * <p><b>Detection vs recognition:</b> detection answers "where are the faces?",
 * recognition answers "whose face is this?". This class only does detection.
 *
 * <p>It uses a Haar cascade (Viola-Jones, 2001): thousands of tiny light/dark rectangle
 * tests arranged in stages. Most non-face windows are rejected by the first few cheap
 * stages, which is why it runs in real time even on a laptop CPU.
 */
public class FaceDetector {

    private static final String CASCADE_RESOURCE = "/haarcascade_frontalface_default.xml";
    private final CascadeClassifier cascade;

    public FaceDetector() throws IOException {
        // CascadeClassifier needs a real file path, but our XML lives inside the jar,
        // so copy it to a temp file first.
        Path tmp = Files.createTempFile("haarcascade", ".xml");
        tmp.toFile().deleteOnExit();
        try (InputStream in = FaceDetector.class.getResourceAsStream(CASCADE_RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + CASCADE_RESOURCE);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        cascade = new CascadeClassifier(tmp.toString());
        if (cascade.empty()) throw new IOException("Could not load face cascade");
    }

    /** Returns a box around every face in a colour (BGR) frame. */
    public List<Rect> detect(Mat bgrFrame) {
        Mat gray = toEqualizedGray(bgrFrame);
        MatOfRect faces = new MatOfRect();
        cascade.detectMultiScale(
                gray, faces,
                AppConfig.SCALE_FACTOR,
                AppConfig.MIN_NEIGHBORS,
                0,
                new Size(AppConfig.MIN_FACE_SIZE, AppConfig.MIN_FACE_SIZE),
                new Size());               // empty = no maximum size
        gray.release();
        return Arrays.asList(faces.toArray());
    }

    /** The biggest face is usually the person standing closest to the camera. */
    public Optional<Rect> largestFace(List<Rect> faces) {
        return faces.stream().max(Comparator.comparingDouble(Rect::area));
    }

    /**
     * Crop the face, convert to grayscale, resize to FACE_SIZE x FACE_SIZE and equalise the
     * histogram. Every face, during registration and attendance, goes through exactly this
     * function, so training and live images always look alike.
     */
    public Mat preprocess(Mat bgrFrame, Rect face) {
        Rect safe = clamp(face, bgrFrame.size());
        Mat crop = new Mat(bgrFrame, safe);
        Mat gray = toEqualizedGray(crop);
        Mat resized = new Mat();
        Imgproc.resize(gray, resized, new Size(AppConfig.FACE_SIZE, AppConfig.FACE_SIZE));
        crop.release();
        gray.release();
        return resized;
    }

    /** Grayscale (colour doesn't help recognition) + histogram equalisation (evens out lighting). */
    private static Mat toEqualizedGray(Mat src) {
        Mat gray = new Mat();
        if (src.channels() == 3) Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
        else src.copyTo(gray);
        Imgproc.equalizeHist(gray, gray);
        return gray;
    }

    /** Keep the box inside the frame, otherwise cropping throws an exception. */
    private static Rect clamp(Rect r, Size frame) {
        int x = Math.max(0, r.x);
        int y = Math.max(0, r.y);
        int w = Math.min(r.width, (int) frame.width - x);
        int h = Math.min(r.height, (int) frame.height - y);
        return new Rect(x, y, w, h);
    }
}
