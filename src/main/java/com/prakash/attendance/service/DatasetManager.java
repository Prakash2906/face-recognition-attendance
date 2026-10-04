package com.prakash.attendance.service;

import com.prakash.attendance.util.ImageUtils;
import com.prakash.attendance.vision.GrayImage;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores the face samples on disk:
 * <pre>
 * dataset/
 *   1/  0.png 1.png ... 29.png     &lt;- student id 1
 *   2/  0.png 1.png ... 29.png     &lt;- student id 2
 * </pre>
 * The folder name is the label, so training data is just "every image in folder N belongs to N".
 */
public class DatasetManager {

    private final Path root;

    public DatasetManager(Path root) {
        this.root = root;
    }

    /** Saves one pre-processed (100x100 grayscale) face. */
    public void saveSample(int studentId, int index, Mat face) throws IOException {
        Path dir = root.resolve(String.valueOf(studentId));
        Files.createDirectories(dir);
        Imgcodecs.imwrite(dir.resolve(index + ".png").toString(), face);
    }

    public int sampleCount(int studentId) throws IOException {
        Path dir = root.resolve(String.valueOf(studentId));
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> p.toString().endsWith(".png")).count();
        }
    }

    /** Removes a student's face samples (used when deleting a student or re-registering). */
    public void deleteSamples(int studentId) throws IOException {
        Path dir = root.resolve(String.valueOf(studentId));
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** Training data: every image, plus a parallel list saying which student it is. */
    public record TrainingData(List<GrayImage> images, List<Integer> labels) {}

    public TrainingData loadAll() throws IOException {
        List<GrayImage> images = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        if (!Files.isDirectory(root)) return new TrainingData(images, labels);

        try (Stream<Path> studentDirs = Files.list(root)) {
            for (Path dir : studentDirs.filter(Files::isDirectory).toList()) {
                int label;
                try {
                    label = Integer.parseInt(dir.getFileName().toString());
                } catch (NumberFormatException e) {
                    continue; // ignore folders that aren't student ids
                }
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path img : files.filter(p -> p.toString().endsWith(".png")).toList()) {
                        Mat m = Imgcodecs.imread(img.toString(), Imgcodecs.IMREAD_GRAYSCALE);
                        if (m.empty()) continue;
                        images.add(ImageUtils.toGrayImage(m));
                        labels.add(label);
                        m.release();
                    }
                }
            }
        }
        return new TrainingData(images, labels);
    }
}
