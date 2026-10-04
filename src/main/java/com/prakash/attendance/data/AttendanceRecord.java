package com.prakash.attendance.data;

/** One row of the attendance report: who was present on a date, and when they were seen. */
public record AttendanceRecord(String rollNo, String name, String department, String date, String time) {}
