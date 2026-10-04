package com.prakash.attendance.data;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All SQL lives here (the DAO pattern), so the rest of the app never writes a query.
 *
 * <pre>
 * students                          attendance
 * ---------------------------       -----------------------------------------
 * id          INTEGER PK  &lt;-------  student_id  INTEGER FK
 * roll_no     TEXT UNIQUE           date        TEXT  (yyyy-MM-dd)
 * name        TEXT                  time        TEXT  (HH:mm:ss)
 * department  TEXT                  UNIQUE(student_id, date)  = once per day
 * created_at  TEXT
 * </pre>
 *
 * The UNIQUE(student_id, date) constraint is the key design choice: the database itself
 * guarantees a student can't be marked twice in one day, even if the camera sees them
 * a hundred times.
 */
public class Database implements AutoCloseable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Connection conn;

    public Database(Path dbFile) throws SQLException, IOException {
        Files.createDirectories(dbFile.toAbsolutePath().getParent());
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");  // SQLite enforces FKs only when asked
            st.execute("""
                    CREATE TABLE IF NOT EXISTS students (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        roll_no     TEXT NOT NULL UNIQUE,
                        name        TEXT NOT NULL,
                        department  TEXT,
                        created_at  TEXT DEFAULT (datetime('now','localtime'))
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS attendance (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        student_id  INTEGER NOT NULL REFERENCES students(id) ON DELETE CASCADE,
                        date        TEXT NOT NULL,
                        time        TEXT NOT NULL,
                        UNIQUE(student_id, date)
                    )""");
        }
    }

    // ------------------------------------------------------------------ students

    /** Adds a student and returns them with their new id. Throws if the roll number exists. */
    public Student addStudent(String rollNo, String name, String department) throws SQLException {
        String sql = "INSERT INTO students(roll_no, name, department) VALUES (?, ?, ?)";
        // PreparedStatement with ? placeholders: values are sent separately from the SQL,
        // which prevents SQL injection.
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rollNo.trim());
            ps.setString(2, name.trim());
            ps.setString(3, department.trim());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Student(keys.getInt(1), rollNo.trim(), name.trim(), department.trim());
            }
        }
    }

    public List<Student> allStudents() throws SQLException {
        List<Student> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, roll_no, name, department FROM students ORDER BY roll_no")) {
            while (rs.next()) list.add(readStudent(rs));
        }
        return list;
    }

    public Optional<Student> findStudent(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, roll_no, name, department FROM students WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readStudent(rs)) : Optional.empty();
            }
        }
    }

    /** Deletes a student; ON DELETE CASCADE removes their attendance rows too. */
    public void deleteStudent(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM students WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    private static Student readStudent(ResultSet rs) throws SQLException {
        return new Student(rs.getInt("id"), rs.getString("roll_no"),
                rs.getString("name"), rs.getString("department"));
    }

    // ------------------------------------------------------------------ attendance

    /**
     * Marks the student present today.
     * @return true if newly marked, false if they were already marked today.
     */
    public boolean markAttendance(int studentId) throws SQLException {
        // INSERT OR IGNORE + the UNIQUE constraint = "insert only if not already there",
        // done atomically by the database (no race between check and insert).
        String sql = "INSERT OR IGNORE INTO attendance(student_id, date, time) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setString(2, LocalDate.now().toString());
            ps.setString(3, LocalTime.now().format(TIME));
            return ps.executeUpdate() == 1;
        }
    }

    public boolean isMarkedToday(int studentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM attendance WHERE student_id = ? AND date = ?")) {
            ps.setInt(1, studentId);
            ps.setString(2, LocalDate.now().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Everyone present on a date, using a JOIN to bring in names from the students table. */
    public List<AttendanceRecord> attendanceOn(LocalDate date) throws SQLException {
        String sql = """
                SELECT s.roll_no, s.name, s.department, a.date, a.time
                FROM attendance a
                JOIN students s ON s.id = a.student_id
                WHERE a.date = ?
                ORDER BY a.time""";
        List<AttendanceRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AttendanceRecord(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
        }
        return list;
    }

    /** Writes a date's attendance to a CSV file (opens in Excel) and returns the file path. */
    public Path exportCsv(LocalDate date, Path dir) throws SQLException, IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("attendance-" + date + ".csv");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println("Roll No,Name,Department,Date,Time");
            for (AttendanceRecord r : attendanceOn(date)) {
                out.printf("%s,%s,%s,%s,%s%n", csv(r.rollNo()), csv(r.name()),
                        csv(r.department()), r.date(), r.time());
            }
        }
        return file;
    }

    /** Quote a CSV field if it contains a comma or quote. */
    private static String csv(String s) {
        if (s == null) return "";
        return (s.contains(",") || s.contains("\"")) ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
