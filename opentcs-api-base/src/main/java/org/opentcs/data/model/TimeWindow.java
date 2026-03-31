package org.opentcs.data.model;

import static java.util.Objects.requireNonNull;

import java.time.LocalTime;

public class TimeWindow {

  public LocalTime getStartTime() {
    return startTime;
  }

  public LocalTime getEndTime() {
    return endTime;
  }

  private LocalTime startTime;
  private LocalTime endTime;

  private TimeWindow(LocalTime startTime, LocalTime endTime) {
    this.startTime = requireNonNull(startTime, "startTime");
    this.endTime = requireNonNull(endTime, "endTime");
  }

  public static TimeWindow of(LocalTime startTime, LocalTime endTime){
    return new TimeWindow(startTime, endTime);
  }

  public TimeWindow withStartTime(LocalTime startTime) {
    return new TimeWindow(startTime, endTime);
  }

  public TimeWindow withEndTime(LocalTime endTime) {
    return new TimeWindow(startTime, endTime);
  }
}
