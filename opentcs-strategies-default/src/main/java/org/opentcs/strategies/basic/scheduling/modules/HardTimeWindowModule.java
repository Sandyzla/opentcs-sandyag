// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.strategies.basic.scheduling.modules;

import static java.util.Objects.requireNonNull;

import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.components.kernel.services.TCSObjectService;
import org.opentcs.customizations.kernel.GlobalSyncObject;
import org.opentcs.data.model.Path;
import org.opentcs.data.model.TCSResource;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.model.Vehicle;
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
  /**
   * Prefix for vehicle properties that define predicted time windows for specific resources.
   */
  public static final String PROPKEY_PREDICTED_TIME_WINDOW_PREFIX
      = "tcs:scheduler:predictedTimeWindow.";
  /**
   * Property key for tolerance (in minutes) used when checking predicted windows.
   */
  public static final String PROPKEY_PREDICTED_TIME_WINDOW_TOLERANCE_MINUTES
      = "tcs:scheduler:predictedTimeWindowToleranceMinutes";
  /**
   * Vehicle property key for overriding the number of hard-reserved claim sets.
   */
  public static final String PROPKEY_HARD_RESERVATION_SET_COUNT
      = "tcs:scheduler:hardReservationSetCount";

  private static final Logger LOG = LoggerFactory.getLogger(HardTimeWindowModule.class);

  private final Object globalSyncObject;
  private final TCSObjectService objectService;
  private final ReservationPriorityResolver priorityResolver;
  private final Clock clock;
  private final Map<String, List<Reservation>> reservationsByClient = new HashMap<>();

  private boolean initialized;

  /**
   * Creates a new module instance using the system default clock.
   *
   * @param globalSyncObject The global synchronization object.
   */
  @Inject
  public HardTimeWindowModule(
      @Nonnull
      TCSObjectService objectService,
      @Nonnull
      ReservationPriorityResolver priorityResolver,
      @Nonnull
      @GlobalSyncObject
      Object globalSyncObject
  ) {
    this(objectService, priorityResolver, globalSyncObject, Clock.systemDefaultZone());
  }

  HardTimeWindowModule(
      TCSObjectService objectService,
      ReservationPriorityResolver priorityResolver,
      Object globalSyncObject,
      Clock clock
  ) {
    this.objectService = requireNonNull(objectService, "objectService");
    this.priorityResolver = requireNonNull(priorityResolver, "priorityResolver");
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
    requireNonNull(client, "client");
    requireNonNull(alloc, "alloc");
    requireNonNull(remainingClaim, "remainingClaim");

    synchronized (globalSyncObject) {
      Vehicle vehicle = fetchVehicleFor(client);
      int hardCount = resolveHardReservationCount(vehicle);
      List<Reservation> clientReservations = new ArrayList<>();
      Instant cursor = Instant.now(clock);

      for (TCSResource<?> resource : alloc) {
        Instant end = cursor.plusSeconds(5);
        clientReservations.add(
            new Reservation(
                resource.getName(),
                cursor,
                end,
                ReservationStrength.HARD
            )
        );
      }

      for (int i = 0; i < remainingClaim.size(); i++) {
        Set<TCSResource<?>> resourceSet = remainingClaim.get(i);
        long seconds = estimateReservationDurationSeconds(resourceSet);
        Instant start = cursor;
        Instant end = cursor.plusSeconds(seconds);
        ReservationStrength strength = i < hardCount
            ? ReservationStrength.HARD
            : ReservationStrength.SOFT;
        for (TCSResource<?> resource : resourceSet) {
          clientReservations.add(new Reservation(resource.getName(), start, end, strength));
        }
        cursor = end;
      }
      reservationsByClient.put(client.getId(), clientReservations);
    }
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
      Vehicle vehicle = fetchVehicleFor(client);
      for (TCSResource<?> resource : resources) {
        String rule = resolveRule(resource, client.getId());
        if (rule == null || rule.isBlank()) {
          if (!mayAllocateByReservationPool(client, vehicle, resource)) {
            return false;
          }
          continue;
        }

        List<TimeRange> windows = parseWindows(rule, resource.getName(), client.getId());
        int toleranceMinutes = resolveToleranceMinutes(resource, vehicle);
        TimeRange selectedWindow = selectMatchingWindow(now, windows, toleranceMinutes);
        if (selectedWindow == null) {
          LOG.debug(
              "{}: Rejecting allocation of resource '{}' due to hard time window '{}'.",
              client.getId(),
              resource.getName(),
              rule
          );
          return false;
        }

        if (hasHigherPriorityOverlap(client, vehicle, resource, selectedWindow, toleranceMinutes)) {
          LOG.debug(
              "{}: Rejecting allocation of resource '{}' due to overlapping reservation of"
                  + " higher-priority vehicle.",
              client.getId(),
              resource.getName()
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

  private Vehicle fetchVehicleFor(Scheduler.Client client) {
    if (client.getRelatedVehicle() != null) {
      return objectService.fetch(Vehicle.class, client.getRelatedVehicle()).orElse(null);
    }
    return objectService.fetch(Vehicle.class, client.getId()).orElse(null);
  }

  private String resolveRule(TCSResource<?> resource, String clientId) {
    String vehicleSpecific = resource.getProperty(PROPKEY_HARD_TIME_WINDOW_PREFIX + clientId);
    if (vehicleSpecific != null) {
      return vehicleSpecific;
    }

    String generic = resource.getProperty(PROPKEY_HARD_TIME_WINDOW);
    if (generic != null) {
      return generic;
    }

    return null;
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

  private int resolveToleranceMinutes(TCSResource<?> resource, Vehicle vehicle) {
    int fallback = 0;
    if (vehicle != null) {
      String vehicleTol = vehicle.getProperty(PROPKEY_PREDICTED_TIME_WINDOW_TOLERANCE_MINUTES);
      if (vehicleTol != null && !vehicleTol.isBlank()) {
        fallback = Integer.parseInt(vehicleTol.trim());
      }
    }
    String resourceTol = resource.getProperty(PROPKEY_PREDICTED_TIME_WINDOW_TOLERANCE_MINUTES);
    if (resourceTol != null && !resourceTol.isBlank()) {
      return Integer.parseInt(resourceTol.trim());
    }
    return fallback;
  }

  private TimeRange selectMatchingWindow(LocalTime now, List<TimeRange> ranges, int toleranceMinutes) {
    for (TimeRange range : ranges) {
      if (range.includesWithTolerance(now, toleranceMinutes)) {
        return range;
      }
    }
    return null;
  }

  private boolean hasHigherPriorityOverlap(
      Scheduler.Client client,
      Vehicle clientVehicle,
      TCSResource<?> resource,
      TimeRange clientWindow,
      int toleranceMinutes
  ) {
    return false;
  }

  private boolean mayAllocateByReservationPool(
      Scheduler.Client client,
      Vehicle clientVehicle,
      TCSResource<?> resource
  ) {
    Reservation ownReservation = selectOwnReservation(client.getId(), resource.getName(), clientVehicle);
    if (ownReservation == null) {
      return true;
    }
    int toleranceMinutes = resolveToleranceMinutes(resource, clientVehicle);
    for (Map.Entry<String, List<Reservation>> entry : reservationsByClient.entrySet()) {
      if (entry.getKey().equals(client.getId())) {
        continue;
      }
      Vehicle otherVehicle = objectService.fetch(Vehicle.class, entry.getKey()).orElse(null);
      for (Reservation other : entry.getValue()) {
        if (!other.resourceName.equals(resource.getName())) {
          continue;
        }
        if (!ownReservation.overlaps(other, toleranceMinutes)) {
          continue;
        }
        if (ownReservation.strength == ReservationStrength.HARD
            && other.strength == ReservationStrength.SOFT) {
          continue;
        }
        if (ownReservation.strength == ReservationStrength.SOFT
            && other.strength == ReservationStrength.HARD) {
          return false;
        }
        if (priorityResolver.compare(client.getId(), clientVehicle, entry.getKey(), otherVehicle) < 0) {
          return false;
        }
      }
    }
    return true;
  }

  private Reservation selectOwnReservation(String clientId, String resourceName, Vehicle vehicle) {
    List<Reservation> reservations = reservationsByClient.get(clientId);
    if (reservations == null || reservations.isEmpty()) {
      return null;
    }
    Instant now = Instant.now(clock);
    int toleranceMinutes = resolveToleranceMinutesByVehicle(vehicle);
    for (Reservation reservation : reservations) {
      if (!reservation.resourceName.equals(resourceName)) {
        continue;
      }
      if (reservation.includes(now, toleranceMinutes)) {
        return reservation;
      }
    }
    return null;
  }

  private int resolveHardReservationCount(Vehicle vehicle) {
    if (vehicle == null) {
      return 1;
    }
    String explicit = vehicle.getProperty(PROPKEY_HARD_RESERVATION_SET_COUNT);
    if (explicit != null && !explicit.isBlank()) {
      return Math.max(0, Integer.parseInt(explicit.trim()));
    }
    if (vehicle.getTransportOrder() == null) {
      return 1;
    }
    TransportOrder order = objectService.fetch(TransportOrder.class, vehicle.getTransportOrder()).orElse(null);
    if (order == null || order.getCurrentDriveOrder() == null) {
      return 1;
    }
    int remainingStepsInCurrentDriveOrder
        = Math.max(1, order.getCurrentDriveOrder().getRoute().getSteps().size() - order.getCurrentRouteStepIndex() - 1);
    return remainingStepsInCurrentDriveOrder;
  }

  private long estimateReservationDurationSeconds(Set<TCSResource<?>> resourceSet) {
    long seconds = 3;
    for (TCSResource<?> resource : resourceSet) {
      if (resource instanceof Path path) {
        int velocity = path.getMaxVelocity() > 0 ? path.getMaxVelocity() : 500;
        long pathSeconds = Math.max(1L, Duration.ofMillis((path.getLength() * 1000L) / velocity).toSeconds());
        seconds = Math.max(seconds, pathSeconds);
      }
    }
    return seconds;
  }

  private int resolveToleranceMinutesByVehicle(Vehicle vehicle) {
    if (vehicle == null) {
      return 0;
    }
    String vehicleTol = vehicle.getProperty(PROPKEY_PREDICTED_TIME_WINDOW_TOLERANCE_MINUTES);
    if (vehicleTol == null || vehicleTol.isBlank()) {
      return 0;
    }
    return Integer.parseInt(vehicleTol.trim());
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

    boolean includesWithTolerance(LocalTime value, int toleranceMinutes) {
      TimeRange adjusted = withTolerance(toleranceMinutes);
      return adjusted.includes(value);
    }

    boolean overlaps(TimeRange other, int toleranceMinutes) {
      TimeRange first = withTolerance(toleranceMinutes);
      TimeRange second = other.withTolerance(toleranceMinutes);
      for (int minute = 0; minute < 24 * 60; minute++) {
        LocalTime probe = LocalTime.MIN.plusMinutes(minute);
        if (first.includes(probe) && second.includes(probe)) {
          return true;
        }
      }
      return false;
    }

    TimeRange withTolerance(int toleranceMinutes) {
      if (toleranceMinutes <= 0) {
        return this;
      }
      return new TimeRange(start.minusMinutes(toleranceMinutes), end.plusMinutes(toleranceMinutes));
    }
  }

  private enum ReservationStrength {
    HARD,
    SOFT
  }

  private static class Reservation {

    private final String resourceName;
    private final Instant start;
    private final Instant end;
    private final ReservationStrength strength;

    Reservation(String resourceName, Instant start, Instant end, ReservationStrength strength) {
      this.resourceName = resourceName;
      this.start = start;
      this.end = end;
      this.strength = strength;
    }

    boolean includes(Instant instant, int toleranceMinutes) {
      Instant startWithTolerance = start.minusSeconds(toleranceMinutes * 60L);
      Instant endWithTolerance = end.plusSeconds(toleranceMinutes * 60L);
      return !instant.isBefore(startWithTolerance) && instant.isBefore(endWithTolerance);
    }

    boolean overlaps(Reservation other, int toleranceMinutes) {
      Instant thisStart = start.minusSeconds(toleranceMinutes * 60L);
      Instant thisEnd = end.plusSeconds(toleranceMinutes * 60L);
      Instant otherStart = other.start.minusSeconds(toleranceMinutes * 60L);
      Instant otherEnd = other.end.plusSeconds(toleranceMinutes * 60L);
      return thisStart.isBefore(otherEnd) && otherStart.isBefore(thisEnd);
    }
  }
}
