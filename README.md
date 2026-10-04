# Face Recognition Attendance System

A desktop app that marks student attendance by recognising faces through a webcam.
Built in **Java 17** with **OpenCV** for the camera and face detection, a **from-scratch LBPH face recognizer**, **SQLite** for storage, and a **Swing** user interface.

---

## 1. What it does

1. **Register** a student by entering a roll number, name and department. The app then captures 30 photos of their face from the webcam.
2. **Train**: the app learns every registered face. This happens automatically after registration.
3. **Take attendance**: students look at the camera. Each recognised face gets a box and a name, and the student is marked present **once per day**.
4. **Review and export** the attendance for any date to a CSV file that opens in Excel.

---

## 2. How to run it

### What you need
- **JDK 17 or newer**: check with `java -version`
- **Maven**: check with `mvn -v`
- A webcam

You don't need to install OpenCV separately. The `org.openpnp:opencv` dependency includes the native libraries for Windows, macOS and Linux.

### Run
```bash
cd face-attendance
mvn compile exec:java
```

### Build a runnable jar
```bash
mvn package
java -jar target/face-attendance-1.0.0.jar
```

The app creates these folders next to where you run it:

| Folder | What's inside |
|---|---|
| `dataset/<studentId>/` | 30 face photos per student (100×100 grayscale PNG) |
| `data/attendance.db` | SQLite database: students and attendance |
| `data/lbph-model.bin` | The trained recognizer |
| `exports/` | CSV exports |

### Using the app
1. **Register tab**: fill in roll number, name and department, click **Register & capture face**, then look at the camera and turn your head slightly left and right until the bar fills.
2. **Attendance tab**: click **Start attendance**. Box colours mean:
   - 🟨 **Amber**, verifying: recognised, waiting for 5 frames in a row
   - 🟩 **Green**, marked: attendance saved (or already marked today)
   - 🟥 **Red**, unknown: not a registered face
3. The number in `[brackets]` is the match distance. If strangers get recognised, move the threshold slider **down**. If registered students show as Unknown, move it **up**.
4. Pick a date and click **Show** to see who was present, or **Export CSV** to save it.

> **On macOS**, the first run asks for camera permission for your terminal or IDE. Allow it, then run the app again.

---

## 3. How it works

```
 Webcam frame
     │
     ▼
 ┌──────────────┐   where are the faces?
 │ FaceDetector │   Haar cascade (Viola–Jones)
 └──────┬───────┘
        │ crop → grayscale → resize 100×100 → equalise histogram
        ▼
 ┌───────────────────┐   whose face is this?
 │ LbphRecognizer    │   Local Binary Patterns Histograms, nearest neighbour
 └──────┬────────────┘
        │ (student id, distance)
        ▼
 ┌───────────────────┐   is it confident and stable?
 │ AttendanceService │   distance < threshold AND 5 frames in a row
 └──────┬────────────┘
        ▼
 ┌──────────┐
 │ Database │   INSERT OR IGNORE … UNIQUE(student_id, date)
 └──────────┘
```

### Project structure
```
src/main/java/com/prakash/attendance/
├── App.java                      entry point, wires everything together
├── config/AppConfig.java         every tunable number in one place
├── vision/
│   ├── OpenCVLoader.java         loads OpenCV's native library
│   ├── Camera.java               webcam wrapper (VideoCapture)
│   ├── FaceDetector.java         Haar cascade detection + pre-processing
│   ├── GrayImage.java            plain-Java grayscale image
│   └── LbphRecognizer.java       ★ the face recognition algorithm, from scratch
├── service/
│   ├── DatasetManager.java       saves/loads face samples on disk
│   ├── RecognitionService.java   trains, saves, loads and runs the recognizer
│   └── AttendanceService.java    threshold + consecutive-frame rule
├── data/
│   ├── Database.java             all SQL (DAO pattern)
│   ├── Student.java
│   └── AttendanceRecord.java
├── ui/
│   ├── MainWindow.java           Swing window + camera thread
│   └── VideoPanel.java           draws the video
└── util/ImageUtils.java          Mat ⇄ BufferedImage ⇄ GrayImage
src/main/resources/haarcascade_frontalface_default.xml   OpenCV's pre-trained face detector
```

### Database
```sql
CREATE TABLE students (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,   -- also the recognizer's label
  roll_no     TEXT NOT NULL UNIQUE,
  name        TEXT NOT NULL,
  department  TEXT,
  created_at  TEXT DEFAULT (datetime('now','localtime'))
);

CREATE TABLE attendance (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  student_id  INTEGER NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  date        TEXT NOT NULL,
  time        TEXT NOT NULL,
  UNIQUE(student_id, date)                         -- once per day, enforced by the DB
);
```

---

## 4. Tuning

All in `AppConfig.java`:

| Setting | Default | Effect |
|---|---|---|
| `SAMPLES_PER_STUDENT` | 30 | More samples cover more angles: better accuracy, slower training |
| `DEFAULT_THRESHOLD` | 90 | Max distance that counts as a match (also adjustable live with the slider) |
| `REQUIRED_CONSECUTIVE_MATCHES` | 5 | Frames in a row needed before marking |
| `MIN_NEIGHBORS` | 5 | Higher means fewer false face detections |
| `MIN_FACE_SIZE` | 80 px | Ignores faces too far from the camera |

## 5. Known limitations
- **Photo spoofing**: holding up a photo of a student can fool it, because there is no liveness check.
- **Lighting and angle**: LBPH works best with front-facing faces and lighting similar to when the student registered.
- **Scale**: comparing against every stored sample is fine for a class of 60, but slow for thousands.

## 6. What I'd improve next
- **Deep-learning embeddings** (e.g. SFace or ArcFace) for better accuracy across angles and lighting
- **Liveness detection** (blink and head-turn checks) to stop photo spoofing
- A **Spring Boot + web dashboard** so teachers can see attendance from any device
- **Subject- and period-wise attendance**, plus alerts for students with low attendance
