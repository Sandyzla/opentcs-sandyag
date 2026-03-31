// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.strategies.basic.scheduling.modules;

import static java.util.Objects.requireNonNull;

import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.customizations.kernel.GlobalSyncObject;
import org.opentcs.data.model.TCSResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces hard time windows for resources.
 * <p>
 * Supported resource properties:
 * </p>
 * <ul>
 * <li>{@value #PROPKEY_HARD_TIME_WINDOW}: Window(s) for all vehicles.</li>
 * <li>{@value #PROPKEY_HARD_TIME_WINDOW_PREFIX}{vehicleName}: Vehicle-specific window(s).</li>
 * </ul>
 * <p>
 * Value format for both properties: {@code HH:mm-HH:mm[,HH:mm-HH:mm...]}. If a range crosses
 * midnight (e.g. {@code 23:00-02:00}), it is handled accordingly.
 * </p>
 */
public class HardTimeWindowModule
    implements
      Scheduler.Module {

  /**
   * Resource property key for all vehicles.
   */
  public static final String PROPKEY_HARD_TIME_WINDOW = "tcs:scheduler:hardTimeWindow";
  /**
   * Prefix for vehicle-specific resource property keys.
   */
  public static final String PROPKEY_HARD_TIME_WINDOW_PREFIX = "tcs:scheduler:hardTimeWindow.";

  private static final Logger LOG = LoggerFactory.getLogger(HardTimeWindowModule.class);

  private final Object globalSyncObject;
  private final Clock clock;

  private boolean initialized;

  /**
   * Creates a new module instance using the system default clock.
   *
   * @param globalSyncObject The global synchronization object.
   */
  @Inject
  public HardTimeWindowModule(
      @Nonnull
      @GlobalSyncObject
      Object globalSyncObject
  ) {
    this(globalSyncObject, Clock.systemDefaultZone());
  }

  HardTimeWindowModule(Object globalSyncObject, Clock clock) {
    this.globalSyncObject = requireNonNull(globalSyncObject, "globalSyncObject");
    this.clock = requireNonNull(clock, "clock");
  }

  @Override
  public void initialize() {
    if (isInitialized()) {
      return;
    }
    initialized = true;
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public void terminate() {
    if (!isInitialized()) {
      return;
    }
    initialized = false;
  }

  @Override
  public void setAllocationState(
      Scheduler.@NonNull Client client,
      @NonNull
      Set<TCSResource<?>> alloc,
      @NonNull
      List<Set<TCSResource<?>>> remainingClaim
  ) {
  }

  @Override
  public boolean mayAllocate(
      Scheduler.@NonNull Client client,
      @NonNull
      Set<TCSResource<?>> resources
  ) {
    requireNonNull(client, "client");
    requireNonNull(resources, "resources");

    synchronized (globalSyncObject) {
      LocalTime now = LocalTime.now(clock);
      for (TCSResource<?> resource : resources) {
        String rule = resolveRule(resource, client.getId());
        if (rule == null || rule.isBlank()) {
          continue;
        }

        if (!matchesAnyWindow(now, parseWindows(rule, resource.getName(), client.getId()))) {
          LOG.debug(
              "{}: Rejecting allocation of resource '{}' due to hard time window '{}'.",
              client.getId(),
              resource.getName(),
              rule
          );
          return false;
        }
      }
      return true;
    }
  }

  @Override
  public void prepareAllocation(
      Scheduler.@NonNull Client client,
      @NonNull
      Set<TCSResource<?>> resources
  ) {
  }

  @Override
  public boolean hasPreparedAllocation(
      Scheduler.@NonNull Client client,
      @NonNull
      Set<TCSResource<?>> resources
  ) {
    return true;
  }

  @Override
  public void allocationReleased(
      Scheduler.@NonNull Client client,
      @NonNull
      Set<TCSResource<?>> resources
  ) {
  }

  private String resolveRule(TCSResource<?> resource, String clientId) {
    String vehicleSpecific = resource.getProperty(PROPKEY_HARD_TIME_WINDOW_PREFIX + clientId);
    return vehicleSpecific != null
        ? vehicleSpecific
        : resource.getProperty(PROPKEY_HARD_TIME_WINDOW);
  }

  private List<TimeRange> parseWindows(String rule, String resourceName, String clientId) {
    List<TimeRange> ranges = new ArrayList<>();
    String[] definitions = rule.split(",");
    for (String def : definitions) {
      String trimmed = def.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] endpoints = trimmed.split("-");
      if (endpoints.length != 2) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid hard time window '%s' for resource '%s' and client '%s'."
                    + " Expected HH:mm-HH:mm.",
                trimmed,
                resourceName,
                clientId
            )
        );
      }
      ranges.add(new TimeRange(parseTime(endpoints[0], trimmed), parseTime(endpoints[1], trimmed)));
    }
    if (ranges.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid hard time window '%s' for resource '%s' and client '%s'.",
              rule,
              resourceName,
              clientId
          )
      );
    }
    return ranges;
  }

  private LocalTime parseTime(String text, String rulePart) {
    try {
      return LocalTime.parse(text.trim());
    }
    catch (DateTimeParseException exc) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid hard time window part '%s'. Expected HH:mm.",
              rulePart
          ),
          exc
      );
    }
  }

  private boolean matchesAnyWindow(LocalTime now, List<TimeRange> ranges) {
    for (TimeRange range : ranges) {
      if (range.includes(now)) {
        return true;
      }
    }
    return false;
  }

  private static class TimeRange {

    private final LocalTime start;
    private final LocalTime end;

    TimeRange(LocalTime start, LocalTime end) {
      this.start = start;
      this.end = end;
    }

    boolean includes(LocalTime value) {
      if (start.equals(end)) {
        return true;
      }
      if (start.isBefore(end)) {
        return !value.isBefore(start) && value.isBefore(end);
      }
      return !value.isBefore(start) || value.isBefore(end);
    }
  }
}
