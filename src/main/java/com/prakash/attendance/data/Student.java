package com.prakash.attendance.data;

/** A registered student. The id is also the label the face recognizer predicts. */
public record Student(int id, String rollNo, String name, String department) {

    @Override
    public String toString() {
        return rollNo + " - " + name;
    }
}
