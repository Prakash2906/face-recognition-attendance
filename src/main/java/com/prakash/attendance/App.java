package com.prakash.attendance;

import com.prakash.attendance.config.AppConfig;
import com.prakash.attendance.data.Database;
import com.prakash.attendance.service.AttendanceService;
import com.prakash.attendance.service.DatasetManager;
import com.prakash.attendance.service.RecognitionService;
import com.prakash.attendance.ui.MainWindow;
import com.prakash.attendance.vision.FaceDetector;
import com.prakash.attendance.vision.OpenCVLoader;

import javax.swing.*;

/**
 * Entry point. Builds every object once and hands them to the window
 * (simple "manual dependency injection": no framework, just constructors).
 *
 * <pre>
 *  Camera -> FaceDetector -> preprocess -> RecognitionService (LBPH) -> AttendanceService -> Database
 *                                 |
 *                                 +-> DatasetManager (registration: save samples)
 * </pre>
 */
public class App {

    public static void main(String[] args) {
        OpenCVLoader.load();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }

        try {
            Database db = new Database(AppConfig.DB_FILE);
            DatasetManager dataset = new DatasetManager(AppConfig.DATASET_DIR);
            RecognitionService recognition = new RecognitionService(dataset, AppConfig.MODEL_FILE);
            recognition.loadModel();
            AttendanceService attendance = new AttendanceService(db);
            FaceDetector detector = new FaceDetector();

            SwingUtilities.invokeLater(() ->
                    new MainWindow(db, dataset, recognition, attendance, detector).start());
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Could not start: " + e.getMessage(),
                    "Face Attendance", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
