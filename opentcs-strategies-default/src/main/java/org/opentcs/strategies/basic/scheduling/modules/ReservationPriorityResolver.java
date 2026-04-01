// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.strategies.basic.scheduling.modules;

import org.opentcs.data.model.Vehicle;

/**
 * Resolves ordering for conflicting resource time window reservations.
 */
public interface ReservationPriorityResolver {

  /**
   * Compares two reservation candidates.
   *
   * @return A positive value if the first candidate should win, a negative value if the second
   * wins, or {@code 0} if both are equivalent.
   */
  int compare(
      String clientId,
      Vehicle clientVehicle,
      String otherClientId,
      Vehicle otherVehicle
  );
}
