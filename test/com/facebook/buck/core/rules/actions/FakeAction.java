/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.Artifact.BuildArtifact;
import com.facebook.buck.core.rules.actions.FakeAction.FakeActionConstructorArgs;
import com.facebook.buck.util.function.TriFunction;
import com.google.common.collect.ImmutableSet;

public class FakeAction extends AbstractAction<FakeActionConstructorArgs> {

  public FakeAction(
      BuildTarget owner,
      ImmutableSet<Artifact> inputs,
      ImmutableSet<BuildArtifact> outputs,
      FakeActionConstructorArgs executeFunction) {
    super(owner, inputs, outputs, executeFunction);
  }

  @Override
  public String getShortName() {
    return "fake-action";
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    return params.apply(inputs, outputs, executionContext);
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  public TriFunction<
          ImmutableSet<Artifact>,
          ImmutableSet<BuildArtifact>,
          ActionExecutionContext,
          ActionExecutionResult>
      getExecuteFunction() {
    return params;
  }

  @FunctionalInterface
  public interface FakeActionConstructorArgs
      extends AbstractAction.ActionConstructorParams,
          TriFunction<
              ImmutableSet<Artifact>,
              ImmutableSet<BuildArtifact>,
              ActionExecutionContext,
              ActionExecutionResult> {}
}
