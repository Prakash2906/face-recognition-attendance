package com.prakash.attendance.ui;

import com.prakash.attendance.config.AppConfig;
import com.prakash.attendance.data.AttendanceRecord;
import com.prakash.attendance.data.Database;
import com.prakash.attendance.data.Student;
import com.prakash.attendance.service.AttendanceService;
import com.prakash.attendance.service.AttendanceService.Decision;
import com.prakash.attendance.service.DatasetManager;
import com.prakash.attendance.service.RecognitionService;
import com.prakash.attendance.util.ImageUtils;
import com.prakash.attendance.vision.Camera;
import com.prakash.attendance.vision.FaceDetector;
import com.prakash.attendance.vision.LbphRecognizer.Prediction;
import org.opencv.core.*;
import org.opencv.core.Point; // explicit: java.awt also has a Point class
import org.opencv.imgproc.Imgproc;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * The main window. Two threads matter here:
 * <ul>
 *   <li><b>Camera thread</b> (runCameraLoop): reads frames, detects and recognises faces.
 *       Heavy work, so it must NOT run on the Swing thread or the window would freeze.</li>
 *   <li><b>Swing Event Dispatch Thread (EDT)</b>: the only thread allowed to touch UI
 *       components. The camera thread hands results over with SwingUtilities.invokeLater.</li>
 * </ul>
 * The camera thread behaves differently depending on {@link Mode}: just preview, capture
 * face samples for a new student, or take attendance.
 */
public class MainWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    private enum Mode { PREVIEW, REGISTERING, ATTENDANCE }

    // BGR colours for drawing on frames (OpenCV order is Blue, Green, Red)
    private static final Scalar GREY = new Scalar(200, 200, 200);
    private static final Scalar VIOLET = new Scalar(255, 71, 91);
    private static final Scalar AMBER = new Scalar(0, 190, 255);
    private static final Scalar GREEN = new Scalar(113, 169, 31);
    private static final Scalar RED = new Scalar(77, 72, 229);

    private final Database db;
    private final DatasetManager dataset;
    private final RecognitionService recognition;
    private final AttendanceService attendance;
    private final FaceDetector detector;

    // State shared with the camera thread -> volatile so changes are seen immediately.
    private volatile Mode mode = Mode.PREVIEW;
    private volatile boolean running = true;
    private volatile Student registering;
    private int capturedSamples;  // only touched by the camera thread
    private int frameCounter;     // only touched by the camera thread

    // UI
    private final VideoPanel video = new VideoPanel();
    private final JLabel status = new JLabel(" ");
    private final JTextField rollField = new JTextField();
    private final JTextField nameField = new JTextField();
    private final JTextField deptField = new JTextField();
    private final JButton registerBtn = new JButton("Register & capture face");
    private final JProgressBar captureProgress = new JProgressBar(0, AppConfig.SAMPLES_PER_STUDENT);
    private final DefaultListModel<Student> studentListModel = new DefaultListModel<>();
    private final JList<Student> studentList = new JList<>(studentListModel);
    private final JButton trainBtn = new JButton("Train model");
    private final JToggleButton attendanceBtn = new JToggleButton("Start attendance");
    private final JLabel thresholdLabel = new JLabel();
    private final JTextField dateField = new JTextField(LocalDate.now().toString(), 10);
    private final DefaultTableModel tableModel =
            new DefaultTableModel(new String[]{"Roll No", "Name", "Dept", "Time"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };

    public MainWindow(Database db, DatasetManager dataset, RecognitionService recognition,
                      AttendanceService attendance, FaceDetector detector) {
        super("Face Recognition Attendance System");
        this.db = db;
        this.dataset = dataset;
        this.recognition = recognition;
        this.attendance = attendance;
        this.detector = detector;
        buildUi();
    }

    /** Shows the window and starts the camera thread. */
    public void start() {
        setVisible(true);
        refreshStudents();
        refreshAttendanceTable();
        setStatus(recognition.isTrained()
                ? "Model loaded. Click Start attendance when ready."
                : "No trained model yet. Register a student to begin.");
        Thread camera = new Thread(this::runCameraLoop, "camera");
        camera.setDaemon(true);
        camera.start();
    }

    // =================================================================== UI layout

    private void buildUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Register", buildRegisterTab());
        tabs.addTab("Attendance", buildAttendanceTab());
        tabs.setPreferredSize(new Dimension(360, 500));

        status.setBorder(new EmptyBorder(6, 10, 6, 10));

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.add(video, BorderLayout.CENTER);
        root.add(tabs, BorderLayout.EAST);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setMinimumSize(new Dimension(980, 600));
        setLocationRelativeTo(null);
    }

    private JPanel buildRegisterTab() {
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Roll number"));
        form.add(rollField);
        form.add(new JLabel("Full name"));
        form.add(nameField);
        form.add(new JLabel("Department"));
        form.add(deptField);
        form.add(registerBtn);
        captureProgress.setStringPainted(true);
        captureProgress.setString("Look at the camera, turn slightly left and right");
        form.add(captureProgress);

        registerBtn.addActionListener(e -> startRegistration());

        JButton deleteBtn = new JButton("Delete selected student");
        deleteBtn.addActionListener(e -> deleteSelectedStudent());

        JPanel list = new JPanel(new BorderLayout(4, 4));
        list.add(new JLabel("Registered students"), BorderLayout.NORTH);
        list.add(new JScrollPane(studentList), BorderLayout.CENTER);
        list.add(deleteBtn, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(8, 12));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(form, BorderLayout.NORTH);
        panel.add(list, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildAttendanceTab() {
        trainBtn.addActionListener(e -> trainAsync());
        attendanceBtn.addActionListener(e -> toggleAttendance());

        JSlider slider = new JSlider(30, 200, (int) AppConfig.DEFAULT_THRESHOLD);
        slider.addChangeListener(e -> {
            attendance.setThreshold(slider.getValue());
            updateThresholdLabel();
        });
        updateThresholdLabel();

        JPanel controls = new JPanel(new GridLayout(0, 1, 4, 4));
        controls.add(trainBtn);
        controls.add(attendanceBtn);
        controls.add(thresholdLabel);
        controls.add(slider);

        JButton loadBtn = new JButton("Show");
        loadBtn.addActionListener(e -> refreshAttendanceTable());
        JButton exportBtn = new JButton("Export CSV");
        exportBtn.addActionListener(e -> exportCsv());
        JPanel dateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        dateRow.add(new JLabel("Date"));
        dateRow.add(dateField);
        dateRow.add(loadBtn);
        dateRow.add(exportBtn);

        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        JPanel records = new JPanel(new BorderLayout(4, 4));
        records.add(dateRow, BorderLayout.NORTH);
        records.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(8, 12));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(records, BorderLayout.CENTER);
        return panel;
    }

    private void updateThresholdLabel() {
        thresholdLabel.setText(String.format("Match threshold: %.0f  (lower = stricter)", attendance.getThreshold()));
    }

    // =================================================================== actions (EDT)

    private void startRegistration() {
        String roll = rollField.getText().trim();
        String name = nameField.getText().trim();
        String dept = deptField.getText().trim();
        if (roll.isEmpty() || name.isEmpty()) {
            setStatus("Enter at least a roll number and a name.");
            return;
        }
        try {
            Student s = db.addStudent(roll, name, dept);
            attendanceBtn.setSelected(false);
            attendanceBtn.setText("Start attendance");
            capturedSamples = 0;   // safe: the camera thread only reads it in REGISTERING mode
            frameCounter = 0;
            registering = s;
            mode = Mode.REGISTERING;
            registerBtn.setEnabled(false);
            captureProgress.setValue(0);
            setStatus("Capturing " + AppConfig.SAMPLES_PER_STUDENT + " face samples for " + name + "...");
        } catch (SQLException ex) {
            setStatus(ex.getMessage().contains("UNIQUE")
                    ? "Roll number " + roll + " is already registered."
                    : "Database error: " + ex.getMessage());
        }
    }

    /** Called (on the EDT) when the camera thread has saved enough samples. */
    private void registrationFinished(Student s) {
        registerBtn.setEnabled(true);
        rollField.setText("");
        nameField.setText("");
        deptField.setText("");
        captureProgress.setString("Done");
        attendance.refreshStudents();
        refreshStudents();
        setStatus("Captured faces for " + s.name() + ". Training...");
        trainAsync();
    }

    private void deleteSelectedStudent() {
        Student s = studentList.getSelectedValue();
        if (s == null) {
            setStatus("Select a student in the list first.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete " + s.name() + " and all their attendance records?",
                "Delete student", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        try {
            db.deleteStudent(s.id());
            dataset.deleteSamples(s.id());
            attendance.refreshStudents();
            refreshStudents();
            refreshAttendanceTable();
            setStatus(recognition.retrainOrClear());
        } catch (Exception ex) {
            setStatus("Could not delete: " + ex.getMessage());
        }
    }

    /** Training takes a moment, so it runs on a background thread via SwingWorker. */
    private void trainAsync() {
        trainBtn.setEnabled(false);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return recognition.train();
            }
            @Override protected void done() {
                trainBtn.setEnabled(true);
                try {
                    setStatus(get());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("Training failed: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void toggleAttendance() {
        if (attendanceBtn.isSelected()) {
            if (!recognition.isTrained()) {
                attendanceBtn.setSelected(false);
                setStatus("Train the model first (register at least one student).");
                return;
            }
            if (mode == Mode.REGISTERING) {
                attendanceBtn.setSelected(false);
                setStatus("Wait for registration to finish.");
                return;
            }
            attendance.refreshStudents();
            mode = Mode.ATTENDANCE;
            attendanceBtn.setText("Stop attendance");
            setStatus("Attendance running. Students: look at the camera for a second.");
        } else {
            mode = Mode.PREVIEW;
            attendanceBtn.setText("Start attendance");
            setStatus("Attendance stopped.");
        }
    }

    private void exportCsv() {
        try {
            Path file = db.exportCsv(selectedDate(), AppConfig.EXPORT_DIR);
            setStatus("Exported " + file.toAbsolutePath());
        } catch (Exception ex) {
            setStatus("Export failed: " + ex.getMessage());
        }
    }

    private LocalDate selectedDate() {
        try {
            return LocalDate.parse(dateField.getText().trim());
        } catch (DateTimeParseException e) {
            dateField.setText(LocalDate.now().toString());
            return LocalDate.now();
        }
    }

    private void refreshStudents() {
        try {
            studentListModel.clear();
            for (Student s : db.allStudents()) studentListModel.addElement(s);
        } catch (SQLException ex) {
            setStatus("Could not load students: " + ex.getMessage());
        }
    }

    private void refreshAttendanceTable() {
        try {
            tableModel.setRowCount(0);
            for (AttendanceRecord r : db.attendanceOn(selectedDate())) {
                tableModel.addRow(new Object[]{r.rollNo(), r.name(), r.department(), r.time()});
            }
        } catch (SQLException ex) {
            setStatus("Could not load attendance: " + ex.getMessage());
        }
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void shutdown() {
        running = false;
        try { Thread.sleep(150); } catch (InterruptedException ignored) { }
        try { db.close(); } catch (SQLException ignored) { }
        dispose();
        System.exit(0);
    }

    // =================================================================== camera thread

    private void runCameraLoop() {
        try (Camera camera = new Camera(AppConfig.CAMERA_INDEX, AppConfig.FRAME_WIDTH, AppConfig.FRAME_HEIGHT)) {
            if (!camera.isOpened()) {
                SwingUtilities.invokeLater(() -> video.setMessage(
                        "No camera found. Check it's connected and not used by another app."));
                return;
            }
            Mat frame = new Mat();
            while (running) {
                if (!camera.read(frame)) {
                    Thread.sleep(10); // camera not ready yet; don't spin the CPU
                    continue;
                }
                List<Rect> faces = detector.detect(frame);

                switch (mode) {
                    case PREVIEW -> faces.forEach(f -> drawBox(frame, f, GREY, null));
                    case REGISTERING -> handleRegistration(frame, faces);
                    case ATTENDANCE -> handleAttendance(frame, faces);
                }

                BufferedImage img = ImageUtils.toBufferedImage(frame);
                SwingUtilities.invokeLater(() -> video.setImage(img));
            }
            frame.release();
        } catch (Exception e) {
            e.printStackTrace();
            SwingUtilities.invokeLater(() -> video.setMessage("Camera error: " + e.getMessage()));
        }
    }

    private void handleRegistration(Mat frame, List<Rect> faces) throws Exception {
        Student s = registering;
        Optional<Rect> face = detector.largestFace(faces);
        if (s == null || face.isEmpty()) {
            putText(frame, "No face - look at the camera", new Point(20, 40), RED);
            return;
        }
        Rect r = face.get();
        drawBox(frame, r, VIOLET, "Capturing " + capturedSamples + "/" + AppConfig.SAMPLES_PER_STUDENT);

        // Save every Nth frame so the samples vary a little (angle, expression).
        if (frameCounter++ % AppConfig.CAPTURE_EVERY_N_FRAMES != 0) return;
        Mat processed = detector.preprocess(frame, r);
        dataset.saveSample(s.id(), capturedSamples, processed);
        processed.release();
        capturedSamples++;

        int done = capturedSamples;
        SwingUtilities.invokeLater(() -> {
            captureProgress.setValue(done);
            captureProgress.setString(done + " / " + AppConfig.SAMPLES_PER_STUDENT);
        });

        if (capturedSamples >= AppConfig.SAMPLES_PER_STUDENT) {
            mode = Mode.PREVIEW;
            registering = null;
            SwingUtilities.invokeLater(() -> registrationFinished(s));
        }
    }

    private void handleAttendance(Mat frame, List<Rect> faces) throws SQLException {
        for (Rect r : faces) {
            Mat processed = detector.preprocess(frame, r);
            Prediction p = recognition.predict(processed);
            processed.release();

            Decision d = attendance.process(p);
            Scalar colour = switch (d.status()) {
                case UNKNOWN -> RED;
                case VERIFYING -> AMBER;
                case MARKED_NOW, ALREADY_MARKED -> GREEN;
            };
            // Show the distance too, it's how you tune the threshold.
            String dist = p.distance() == Double.MAX_VALUE ? "" : String.format("  [%.0f]", p.distance());
            drawBox(frame, r, colour, d.label() + dist);

            if (d.status() == AttendanceService.Status.MARKED_NOW) {
                String name = d.student().name();
                SwingUtilities.invokeLater(() -> {
                    setStatus(name + " marked present at " + java.time.LocalTime.now().withNano(0));
                    refreshAttendanceTable();
                    Toolkit.getDefaultToolkit().beep();
                });
            }
        }
        attendance.endFrame();
    }

    // =================================================================== drawing helpers

    private static void drawBox(Mat frame, Rect r, Scalar colour, String label) {
        Imgproc.rectangle(frame, r.tl(), r.br(), colour, 2);
        if (label == null) return;
        int[] baseline = new int[1];
        Size ts = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.55, 1, baseline);
        Point tl = new Point(r.x, Math.max(0, r.y - ts.height - 10));
        Imgproc.rectangle(frame, tl, new Point(r.x + ts.width + 10, tl.y + ts.height + 10), colour, -1);
        Imgproc.putText(frame, label, new Point(r.x + 5, tl.y + ts.height + 4),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.55, new Scalar(255, 255, 255), 1, Imgproc.LINE_AA);
    }

    private static void putText(Mat frame, String text, Point at, Scalar colour) {
        Imgproc.putText(frame, text, at, Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, colour, 2, Imgproc.LINE_AA);
    }
}
