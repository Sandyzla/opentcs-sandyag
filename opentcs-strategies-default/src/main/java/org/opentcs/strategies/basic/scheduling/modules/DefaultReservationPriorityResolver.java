// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.strategies.basic.scheduling.modules;

import jakarta.inject.Singleton;
import org.opentcs.data.model.Vehicle;

/**
 * Default priority resolver based on vehicle property values.
 */
@Singleton
public class DefaultReservationPriorityResolver
    implements
      ReservationPriorityResolver {

  /**
   * Property key for reservation priority on vehicles.
   */
  public static final String PROPKEY_RESERVATION_PRIORITY = "tcs:scheduler:reservationPriority";

  @Override
  public int compare(
      String clientId,
      Vehicle clientVehicle,
      String otherClientId,
      Vehicle otherVehicle
  ) {
    int clientPriority = priorityFrom(clientVehicle);
    int otherPriority = priorityFrom(otherVehicle);
    int byPriority = Integer.compare(clientPriority, otherPriority);
    if (byPriority != 0) {
      return byPriority;
    }
    // Stable tie-breaker to keep decisions deterministic.
    return -clientId.compareTo(otherClientId);
  }

  private int priorityFrom(Vehicle vehicle) {
    if (vehicle == null) {
      return 0;
    }
    String value = vehicle.getProperty(PROPKEY_RESERVATION_PRIORITY);
    if (value == null || value.isBlank()) {
      return 0;
    }
    return Integer.parseInt(value.trim());
  }
}
