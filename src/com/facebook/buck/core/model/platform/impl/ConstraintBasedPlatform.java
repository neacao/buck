/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.core.model.platform.impl;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;

/** An implementation of a {@link Platform} that has a fixed set of constraints. */
public class ConstraintBasedPlatform implements Platform {
  private final UnconfiguredBuildTargetView buildTarget;
  private final ImmutableSet<ConstraintValue> constraintValues;

  public ConstraintBasedPlatform(
      UnconfiguredBuildTargetView buildTarget, ImmutableSet<ConstraintValue> constraintValues) {
    this.buildTarget = buildTarget;
    this.constraintValues = constraintValues;
  }

  /**
   * A platform matches the given constraints when these constraints are present in the platform
   * constraints (platform constraints is a superset of the provided constraints).
   */
  @Override
  public boolean matchesAll(Collection<ConstraintValue> constraintValues) {
    return this.constraintValues.containsAll(constraintValues);
  }

  @Override
  public String toString() {
    return buildTarget.getFullyQualifiedName();
  }

  /** @return the build target of the {@code platform} rule where this platform is declared. */
  public UnconfiguredBuildTargetView getBuildTarget() {
    return buildTarget;
  }

  public ImmutableSet<ConstraintValue> getConstraintValues() {
    return constraintValues;
  }
}
