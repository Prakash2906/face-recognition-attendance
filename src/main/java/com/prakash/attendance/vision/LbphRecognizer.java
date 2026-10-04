package com.prakash.attendance.vision;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * LBPH = Local Binary Patterns Histograms. A classic face recognition algorithm,
 * written here from scratch in plain Java (same idea as OpenCV's LBPHFaceRecognizer).
 *
 * <h2>The idea in 4 steps</h2>
 * <ol>
 *   <li><b>LBP code per pixel.</b> Compare a pixel with its 8 neighbours. Neighbour &gt;= centre
 *       gives 1, otherwise 0. Reading the 8 bits clockwise gives a number 0..255 that describes
 *       the local texture: an edge, a corner, a flat patch, a spot.</li>
 *   <li><b>Split into a grid.</b> Divide the LBP image into 8 x 8 = 64 cells, so we remember
 *       <i>where</i> each texture appears (eyes are at the top, mouth at the bottom).</li>
 *   <li><b>Histogram per cell.</b> Count how often each of the 256 codes appears in a cell,
 *       then join all 64 histograms into one long vector (64 x 256 = 16,384 numbers).
 *       That vector is the face's "fingerprint".</li>
 *   <li><b>Compare.</b> To recognise a new face, build its vector and find the training vector
 *       with the smallest chi-square distance. If that distance is under a threshold, it's that
 *       person; otherwise it's "unknown".</li>
 * </ol>
 *
 * <h2>Why LBP works for faces</h2>
 * The code only depends on whether neighbours are brighter or darker than the centre, not on
 * absolute brightness. So turning the lights up or down barely changes it.
 */
public class LbphRecognizer {

    private static final int RADIUS = 1;
    private static final int NEIGHBORS = 8;
    private static final int BINS = 1 << NEIGHBORS; // 256 possible codes
    private static final int FILE_MAGIC = 0x4C425048;  // "LBPH"

    // Offsets of the 8 neighbours, clockwise from top-left.
    private static final int[] DX = {-1, 0, 1, 1, 1, 0, -1, -1};
    private static final int[] DY = {-1, -1, -1, 0, 1, 1, 1, 0};

    private final int gridX;
    private final int gridY;

    /** One histogram vector per training image, and the student id it belongs to. */
    private final List<double[]> histograms = new ArrayList<>();
    private final List<Integer> labels = new ArrayList<>();

    public LbphRecognizer(int gridX, int gridY) {
        this.gridX = gridX;
        this.gridY = gridY;
    }

    /** Result of a prediction: the closest student id and how close (lower = more similar). */
    public record Prediction(int label, double distance) {
        public static final Prediction NONE = new Prediction(-1, Double.MAX_VALUE);
    }

    // ------------------------------------------------------------------ training

    /** Learn from face images. labels.get(i) is the student id for images.get(i). */
    public void train(List<GrayImage> images, List<Integer> labels) {
        if (images.size() != labels.size()) {
            throw new IllegalArgumentException("Need exactly one label per image");
        }
        this.histograms.clear();
        this.labels.clear();
        for (int i = 0; i < images.size(); i++) {
            this.histograms.add(featureVector(images.get(i)));
            this.labels.add(labels.get(i));
        }
    }

    public boolean isTrained() {
        return !histograms.isEmpty();
    }

    public int sampleCount() {
        return histograms.size();
    }

    // ------------------------------------------------------------------ prediction

    /** Nearest-neighbour search: compare against every training sample and keep the closest. */
    public Prediction predict(GrayImage face) {
        if (!isTrained()) return Prediction.NONE;
        double[] query = featureVector(face);
        int bestLabel = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < histograms.size(); i++) {
            double d = chiSquare(query, histograms.get(i));
            if (d < bestDistance) {
                bestDistance = d;
                bestLabel = labels.get(i);
            }
        }
        return new Prediction(bestLabel, bestDistance);
    }

    // ------------------------------------------------------------------ the algorithm

    /** Step 1: replace every pixel with its 8-bit Local Binary Pattern code. */
    static int[][] lbpImage(GrayImage img) {
        int w = img.width() - 2 * RADIUS;   // border pixels have no full neighbourhood
        int h = img.height() - 2 * RADIUS;
        int[][] out = new int[h][w];
        for (int y = RADIUS; y < img.height() - RADIUS; y++) {
            for (int x = RADIUS; x < img.width() - RADIUS; x++) {
                int center = img.at(x, y);
                int code = 0;
                for (int n = 0; n < NEIGHBORS; n++) {
                    int neighbour = img.at(x + DX[n], y + DY[n]);
                    // set bit n if the neighbour is at least as bright as the centre
                    if (neighbour >= center) code |= (1 << (NEIGHBORS - 1 - n));
                }
                out[y - RADIUS][x - RADIUS] = code;
            }
        }
        return out;
    }

    /** Steps 2 and 3: grid of cells, one normalised 256-bin histogram per cell, joined end to end. */
    double[] featureVector(GrayImage img) {
        int[][] lbp = lbpImage(img);
        int h = lbp.length;
        int w = lbp[0].length;
        int cellW = w / gridX;
        int cellH = h / gridY;
        double[] vector = new double[gridX * gridY * BINS];

        for (int gy = 0; gy < gridY; gy++) {
            for (int gx = 0; gx < gridX; gx++) {
                int offset = (gy * gridX + gx) * BINS;
                for (int y = gy * cellH; y < (gy + 1) * cellH; y++) {
                    for (int x = gx * cellW; x < (gx + 1) * cellW; x++) {
                        vector[offset + lbp[y][x]]++;
                    }
                }
                // Normalise so each cell's histogram sums to 1 (independent of cell size).
                double cellPixels = (double) cellW * cellH;
                for (int b = 0; b < BINS; b++) vector[offset + b] /= cellPixels;
            }
        }
        return vector;
    }

    /**
     * Step 4: alternative chi-square distance (what OpenCV's LBPH uses):
     *   d = 2 * sum( (a - b)^2 / (a + b) )
     * Identical histograms give 0. The more they differ, the bigger it gets.
     */
    static double chiSquare(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double s = a[i] + b[i];
            if (s > 0) {
                double diff = a[i] - b[i];
                sum += diff * diff / s;
            }
        }
        return 2 * sum;
    }

    // ------------------------------------------------------------------ save / load

    /** Saves the model so the app doesn't need to retrain every time it starts. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(gridX);
            out.writeInt(gridY);
            out.writeInt(histograms.size());
            for (int i = 0; i < histograms.size(); i++) {
                out.writeInt(labels.get(i));
                double[] hist = histograms.get(i);
                out.writeInt(hist.length);
                for (double v : hist) out.writeFloat((float) v); // float halves the file size
            }
        }
    }

    public static LbphRecognizer load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FILE_MAGIC) throw new IOException("Not an LBPH model file: " + file);
            LbphRecognizer r = new LbphRecognizer(in.readInt(), in.readInt());
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                r.labels.add(in.readInt());
                double[] hist = new double[in.readInt()];
                for (int j = 0; j < hist.length; j++) hist[j] = in.readFloat();
                r.histograms.add(hist);
            }
            return r;
        }
    }
}
