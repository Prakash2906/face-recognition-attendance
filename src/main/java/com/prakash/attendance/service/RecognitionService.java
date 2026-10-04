package com.prakash.attendance.service;

import com.prakash.attendance.config.AppConfig;
import com.prakash.attendance.util.ImageUtils;
import com.prakash.attendance.vision.LbphRecognizer;
import com.prakash.attendance.vision.LbphRecognizer.Prediction;
import org.opencv.core.Mat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Owns the recognizer: trains it from the dataset, saves/loads it, and answers "who is this?". */
public class RecognitionService {

    private final DatasetManager dataset;
    private final Path modelFile;
    private volatile LbphRecognizer recognizer;

    public RecognitionService(DatasetManager dataset, Path modelFile) {
        this.dataset = dataset;
        this.modelFile = modelFile;
        this.recognizer = new LbphRecognizer(AppConfig.LBPH_GRID_X, AppConfig.LBPH_GRID_Y);
    }

    /** Loads the saved model if there is one. Returns true if a model was loaded. */
    public boolean loadModel() {
        if (!Files.exists(modelFile)) return false;
        try {
            recognizer = LbphRecognizer.load(modelFile);
            return recognizer.isTrained();
        } catch (IOException e) {
            System.err.println("Could not load model, retrain needed: " + e.getMessage());
            return false;
        }
    }

    /** Trains on every saved face sample and saves the model. Returns a short summary. */
    public String train() throws IOException {
        DatasetManager.TrainingData data = dataset.loadAll();
        if (data.images().isEmpty()) {
            throw new IllegalStateException("No face samples yet. Register a student first.");
        }
        LbphRecognizer fresh = new LbphRecognizer(AppConfig.LBPH_GRID_X, AppConfig.LBPH_GRID_Y);
        fresh.train(data.images(), data.labels());
        fresh.save(modelFile);
        recognizer = fresh; // swap in atomically; the camera thread keeps using the old one until now
        int students = new HashSet<>(data.labels()).size();
        return "Trained on " + data.images().size() + " samples from " + students + " student(s).";
    }

    /** Retrains after a student is deleted; if nobody is left, clears the model instead. */
    public String retrainOrClear() throws IOException {
        if (dataset.loadAll().images().isEmpty()) {
            recognizer = new LbphRecognizer(AppConfig.LBPH_GRID_X, AppConfig.LBPH_GRID_Y);
            Files.deleteIfExists(modelFile);
            return "No students left. Model cleared.";
        }
        return train();
    }

    public boolean isTrained() {
        return recognizer.isTrained();
    }

    /** @param face a pre-processed face from FaceDetector.preprocess */
    public Prediction predict(Mat face) {
        return recognizer.predict(ImageUtils.toGrayImage(face));
    }
}
