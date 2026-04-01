// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.strategies.basic.scheduling.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.annotation.Nonnull;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.components.kernel.services.TCSObjectService;
import org.opentcs.data.TCSObjectReference;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.TCSResource;
import org.opentcs.data.model.Vehicle;

/**
 * Unit tests for {@link HardTimeWindowModule}.
 */
class HardTimeWindowModuleTest {

  @Test
  void allowsAllocationInsideGlobalWindow() {
    HardTimeWindowModule module = createModuleAt("2026-04-01T10:00:00Z");
    Scheduler.Client client = new SampleClient("vehicle-01");
    Point point = new Point("P1")
        .withProperty(HardTimeWindowModule.PROPKEY_HARD_TIME_WINDOW, "09:30-10:30");

    assertTrue(module.mayAllocate(client, Set.of(point)));
  }

  @Test
  void rejectsAllocationOutsideGlobalWindow() {
    HardTimeWindowModule module = createModuleAt("2026-04-01T12:00:00Z");
    Scheduler.Client client = new SampleClient("vehicle-01");
    Point point = new Point("P1")
        .withProperty(HardTimeWindowModule.PROPKEY_HARD_TIME_WINDOW, "09:30-10:30");

    assertFalse(module.mayAllocate(client, Set.of(point)));
  }

  @Test
  void usesVehicleSpecificWindowIfPresent() {
    HardTimeWindowModule module = createModuleAt("2026-04-01T12:00:00Z");
    Scheduler.Client client = new SampleClient("vehicle-01");
    Point point = new Point("P1")
        .withProperty(HardTimeWindowModule.PROPKEY_HARD_TIME_WINDOW, "09:30-10:30")
        .withProperty(
            HardTimeWindowModule.PROPKEY_HARD_TIME_WINDOW_PREFIX + "vehicle-01",
            "11:00-13:00"
        );

    assertTrue(module.mayAllocate(client, Set.of(point)));
  }

  @Test
  void supportsCrossMidnightWindow() {
    HardTimeWindowModule module = createModuleAt("2026-04-01T23:30:00Z");
    Scheduler.Client client = new SampleClient("vehicle-01");
    Point point = new Point("P1")
        .withProperty(HardTimeWindowModule.PROPKEY_HARD_TIME_WINDOW, "23:00-01:00");

    assertTrue(module.mayAllocate(client, Set.of(point)));
  }

  @Test
  void throwsForInvalidWindowFormat() {
    HardTimeWindowModule module = createModuleAt("2026-04-01T10:00:00Z");
    Scheduler.Client client = new SampleClient("vehicle-01");
    Point point = new Point("P1")
        .withProperty(HardTimeWindowModule.PROPKEY_HARD_TIME_WINDOW, "bad-format");

    assertThrows(IllegalArgumentException.class, () -> module.mayAllocate(client, Set.of(point)));
  }

  @Test
  void allowsAllocationForHigherPriorityOnConflictingReservation() {
    Clock fixedClock = Clock.fixed(Instant.parse("2026-04-01T10:00:00Z"), ZoneId.of("UTC"));
    TCSObjectService objectService = mock(TCSObjectService.class);
    Vehicle vehicle01 = new Vehicle("vehicle-01")
        .withProperty(DefaultReservationPriorityResolver.PROPKEY_RESERVATION_PRIORITY, "1");
    Vehicle vehicle02 = new Vehicle("vehicle-02")
        .withProperty(DefaultReservationPriorityResolver.PROPKEY_RESERVATION_PRIORITY, "2");
    when(objectService.fetch(Vehicle.class, "vehicle-01")).thenReturn(java.util.Optional.of(vehicle01));
    when(objectService.fetch(Vehicle.class, "vehicle-02")).thenReturn(java.util.Optional.of(vehicle02));
    when(objectService.fetch(Vehicle.class)).thenReturn(Set.of(vehicle01, vehicle02));
    HardTimeWindowModule module = new HardTimeWindowModule(
        objectService,
        new DefaultReservationPriorityResolver(),
        new Object(),
        fixedClock
    );
    Point point = new Point("P1");
    module.setAllocationState(new SampleClient("vehicle-01"), Set.of(), List.of(Set.of(point)));
    module.setAllocationState(new SampleClient("vehicle-02"), Set.of(), List.of(Set.of(point)));

    assertTrue(module.mayAllocate(new SampleClient("vehicle-02"), Set.of(point)));
    assertFalse(module.mayAllocate(new SampleClient("vehicle-01"), Set.of(point)));
  }

  private HardTimeWindowModule createModuleAt(String isoInstant) {
    Clock fixedClock = Clock.fixed(Instant.parse(isoInstant), ZoneId.of("UTC"));
    TCSObjectService objectService = mock(TCSObjectService.class);
    when(objectService.fetch(Vehicle.class)).thenReturn(Set.of());
    return new HardTimeWindowModule(
        objectService,
        new DefaultReservationPriorityResolver(),
        new Object(),
        fixedClock
    );
  }

  private static class SampleClient
      implements
        Scheduler.Client {

    private final String id;

    SampleClient(String id) {
      this.id = id;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public TCSObjectReference<Vehicle> getRelatedVehicle() {
      return null;
    }

    @Override
    public boolean onAllocation(
        @Nonnull
        Set<TCSResource<?>> resources
    ) {
      return true;
    }
  }
}
