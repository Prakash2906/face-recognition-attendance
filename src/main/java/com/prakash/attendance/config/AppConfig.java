package com.prakash.attendance.config;

import java.nio.file.Path;

/**
 * Every tunable number in the project lives here, so you can explain (and change) each one.
 */
public final class AppConfig {

    private AppConfig() {}

    // ---------- Files ----------
    /** Face samples are saved as dataset/&lt;studentId&gt;/&lt;n&gt;.png */
    public static final Path DATASET_DIR = Path.of("dataset");
    /** The SQLite database file. */
    public static final Path DB_FILE = Path.of("data", "attendance.db");
    /** The trained LBPH model (all histograms + labels), so we don't retrain on every start. */
    public static final Path MODEL_FILE = Path.of("data", "lbph-model.bin");
    /** CSV exports land here. */
    public static final Path EXPORT_DIR = Path.of("exports");

    // ---------- Camera ----------
    public static final int CAMERA_INDEX = 0;     // 0 = built-in webcam
    public static final int FRAME_WIDTH = 640;
    public static final int FRAME_HEIGHT = 480;

    // ---------- Face detection (Haar cascade) ----------
    /** How much the image is shrunk at each detection scale. Smaller = more accurate, slower. */
    public static final double SCALE_FACTOR = 1.1;
    /** How many overlapping detections a face needs to be accepted. Higher = fewer false faces. */
    public static final int MIN_NEIGHBORS = 5;
    /** Ignore faces smaller than this (pixels) - they're too far away to recognise reliably. */
    public static final int MIN_FACE_SIZE = 80;

    // ---------- Pre-processing ----------
    /** Every face is cropped and resized to FACE_SIZE x FACE_SIZE grayscale before recognition. */
    public static final int FACE_SIZE = 100;

    // ---------- Registration ----------
    /** Face photos captured per student. More samples = more angles = better recognition. */
    public static final int SAMPLES_PER_STUDENT = 30;
    /** Capture one sample every N frames so the samples aren't all identical. */
    public static final int CAPTURE_EVERY_N_FRAMES = 3;

    // ---------- Recognition (LBPH) ----------
    public static final int LBPH_GRID_X = 8;
    public static final int LBPH_GRID_Y = 8;
    /**
     * Chi-square distance below which a face counts as a match.
     * Lower distance = more similar. Tune it from the UI slider while watching the
     * numbers drawn on screen: known faces usually sit well below unknown ones.
     */
    public static final double DEFAULT_THRESHOLD = 90.0;

    // ---------- Attendance ----------
    /**
     * A student is marked only after being recognised in this many frames in a row.
     * One lucky frame can be wrong; five in a row almost never is.
     */
    public static final int REQUIRED_CONSECUTIVE_MATCHES = 5;
}
