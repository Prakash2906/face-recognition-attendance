package com.prakash.attendance.service;

import com.prakash.attendance.config.AppConfig;
import com.prakash.attendance.data.Database;
import com.prakash.attendance.data.Student;
import com.prakash.attendance.vision.LbphRecognizer.Prediction;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns per-frame predictions into attendance decisions.
 *
 * <p>A single frame can be wrong (blur, bad angle, someone similar). So a student is only
 * marked after being recognised in {@link AppConfig#REQUIRED_CONSECUTIVE_MATCHES} frames
 * in a row. Any frame where they aren't the match resets their streak. This one rule
 * removes most false attendance.
 *
 * <p>Per frame: call {@link #process} for each face, then {@link #endFrame} once.
 * Methods are synchronized because the camera thread and the UI thread both call in.
 */
public class AttendanceService {

    public enum Status { UNKNOWN, VERIFYING, MARKED_NOW, ALREADY_MARKED }

    /** What to draw above a face: who it is, and what happened. */
    public record Decision(Status status, Student student, double distance, int streak) {
        public String label() {
            return switch (status) {
                case UNKNOWN -> "Unknown";
                case VERIFYING -> student.name() + "  verifying " + streak + "/" + AppConfig.REQUIRED_CONSECUTIVE_MATCHES;
                case MARKED_NOW -> student.name() + "  marked present";
                case ALREADY_MARKED -> student.name() + "  already marked";
            };
        }
    }

    private final Database db;
    private final Map<Integer, Integer> streaks = new HashMap<>();
    private final Set<Integer> seenThisFrame = new HashSet<>();
    private final Map<Integer, Student> studentCache = new HashMap<>();
    private volatile double threshold = AppConfig.DEFAULT_THRESHOLD;

    public AttendanceService(Database db) {
        this.db = db;
    }

    public void setThreshold(double threshold) { this.threshold = threshold; }
    public double getThreshold() { return threshold; }

    /** Call once per detected face, per frame. */
    public synchronized Decision process(Prediction p) throws SQLException {
        // 1. Too far from every known face -> unknown.
        if (p.label() < 0 || p.distance() > threshold) {
            return new Decision(Status.UNKNOWN, null, p.distance(), 0);
        }
        Optional<Student> student = student(p.label());
        if (student.isEmpty()) { // model still knows someone who was deleted; retrain fixes it
            return new Decision(Status.UNKNOWN, null, p.distance(), 0);
        }
        Student s = student.get();

        // 2. Already present today? Nothing to do.
        if (db.isMarkedToday(s.id())) {
            return new Decision(Status.ALREADY_MARKED, s, p.distance(), 0);
        }

        // 3. Build up the streak; mark when it's long enough.
        seenThisFrame.add(s.id());
        int streak = streaks.merge(s.id(), 1, Integer::sum);

        if (streak >= AppConfig.REQUIRED_CONSECUTIVE_MATCHES) {
            streaks.remove(s.id());
            boolean marked = db.markAttendance(s.id());
            return new Decision(marked ? Status.MARKED_NOW : Status.ALREADY_MARKED, s, p.distance(), streak);
        }
        return new Decision(Status.VERIFYING, s, p.distance(), streak);
    }

    /**
     * Call after processing every face in a frame. Anyone who wasn't recognised in this
     * frame loses their streak, so "in a row" really means consecutive frames.
     * Works with several people in front of the camera at once.
     */
    public synchronized void endFrame() {
        streaks.keySet().retainAll(seenThisFrame);
        seenThisFrame.clear();
    }

    /** Clear cached names after students are added or deleted. */
    public synchronized void refreshStudents() {
        studentCache.clear();
        streaks.clear();
        seenThisFrame.clear();
    }

    private Optional<Student> student(int id) throws SQLException {
        Student cached = studentCache.get(id);
        if (cached != null) return Optional.of(cached);
        Optional<Student> s = db.findStudent(id);
        s.ifPresent(st -> studentCache.put(id, st));
        return s;
    }
}
