/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.DexProducedFromJavaLibrary.BuildOutput;
import com.facebook.buck.android.DxStep.Option;
import com.facebook.buck.android.dalvik.EstimateDexWeightStep;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.zip.ZipScrubberStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * {@link DexProducedFromJavaLibrary} is a {@link BuildRule} that serves a very specific purpose: it
 * takes a {@link JavaLibrary} and dexes the output of the {@link JavaLibrary} if its list of
 * classes is non-empty. Because it is expected to be used with pre-dexing, we always pass the
 * {@code --force-jumbo} flag to {@code dx} in this buildable.
 *
 * <p>Most {@link BuildRule}s can determine the (possibly null) path to their output file from their
 * definition. This is an anomaly because we do not know whether this will write a {@code .dex} file
 * until runtime. Unfortunately, because there is no such thing as an empty {@code .dex} file, we
 * cannot write a meaningful "dummy .dex" if there are no class files to pass to {@code dx}.
 */
public class DexProducedFromJavaLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements SupportsInputBasedRuleKey, InitializableFromDisk<BuildOutput> {

  @VisibleForTesting static final String WEIGHT_ESTIMATE = "weight_estimate";
  @VisibleForTesting static final String CLASSNAMES_TO_HASHES = "classnames_to_hashes";
  @VisibleForTesting static final String REFERENCED_RESOURCES = "referenced_resources";

  @AddToRuleKey private final SourcePath javaLibrarySourcePath;
  @AddToRuleKey private final String dexTool;
  /** Scale factor to apply to our weight estimate, for deceptive dexes. */
  @AddToRuleKey private final int weightFactor;

  @AddToRuleKey @Nullable private final ImmutableSortedSet<SourcePath> desugarDeps;

  private final AndroidPlatformTarget androidPlatformTarget;
  private final JavaLibrary javaLibrary;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  DexProducedFromJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams params,
      JavaLibrary javaLibrary) {
    this(buildTarget, projectFilesystem, androidPlatformTarget, params, javaLibrary, DxStep.DX);
  }

  DexProducedFromJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams params,
      JavaLibrary javaLibrary,
      String dexTool) {
    this(buildTarget, projectFilesystem, androidPlatformTarget, params, javaLibrary, dexTool, 1);
  }

  DexProducedFromJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams params,
      JavaLibrary javaLibrary,
      String dexTool,
      int weightFactor) {
    this(
        buildTarget,
        projectFilesystem,
        androidPlatformTarget,
        params,
        javaLibrary,
        dexTool,
        weightFactor,
        null);
  }

  DexProducedFromJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams params,
      JavaLibrary javaLibrary,
      String dexTool,
      int weightFactor,
      @Nullable ImmutableSortedSet<BuildRule> desugarDeps) {
    super(
        buildTarget,
        projectFilesystem,
        desugarDeps != null ? params.withExtraDeps(desugarDeps) : params);
    this.androidPlatformTarget = androidPlatformTarget;
    this.javaLibrary = javaLibrary;
    this.dexTool = dexTool;
    this.javaLibrarySourcePath = javaLibrary.getSourcePathToOutput();
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
    this.weightFactor = weightFactor;
    this.desugarDeps = desugarDeps != null ? getDesugarClassPaths(desugarDeps) : null;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getPathToDex())));

    // Make sure that the buck-out/gen/ directory exists for this.buildTarget.
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                getPathToDex().getParent())));

    // If there are classes, run dx.
    ImmutableSortedMap<String, HashCode> classNamesToHashes = javaLibrary.getClassNamesToHashes();
    boolean hasClassesToDx = !classNamesToHashes.isEmpty();
    Supplier<Integer> weightEstimate;

    @Nullable DxStep dx;

    if (hasClassesToDx) {
      Path pathToOutputFile =
          context.getSourcePathResolver().getAbsolutePath(javaLibrarySourcePath);
      EstimateDexWeightStep estimate =
          new EstimateDexWeightStep(getProjectFilesystem(), pathToOutputFile);
      steps.add(estimate);
      weightEstimate = estimate;

      // To be conservative, use --force-jumbo for these intermediate .dex files so that they can be
      // merged into a final classes.dex that uses jumbo instructions.
      EnumSet<DxStep.Option> options =
          EnumSet.of(
              DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
              DxStep.Option.RUN_IN_PROCESS,
              DxStep.Option.NO_OPTIMIZE,
              DxStep.Option.FORCE_JUMBO);
      if (!javaLibrary.isDesugarEnabled()) {
        options.add(Option.NO_DESUGAR);
      }
      dx =
          new DxStep(
              getProjectFilesystem(),
              androidPlatformTarget,
              getPathToDex(),
              Collections.singleton(pathToOutputFile),
              options,
              Optional.empty(),
              dexTool,
              dexTool.equals(DxStep.D8),
              desugarDeps != null
                  ? getAbsolutePaths(desugarDeps, context.getSourcePathResolver())
                  : null,
              Optional.empty());
      steps.add(dx);

      // The `DxStep` delegates to android tools to build a ZIP with timestamps in it, making
      // the output non-deterministic.  So use an additional scrubbing step to zero these out.
      steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(getPathToDex())));

    } else {
      dx = null;
      weightEstimate = Suppliers.ofInstance(0);
    }

    // Run a step to record artifacts and metadata. The values recorded depend upon whether dx was
    // run.
    String stepName = hasClassesToDx ? "record_dx_success" : "record_empty_dx";
    AbstractExecutionStep recordArtifactAndMetadataStep =
        new AbstractExecutionStep(stepName) {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            if (hasClassesToDx) {
              buildableContext.recordArtifact(getPathToDex());

              @Nullable Collection<String> referencedResources = dx.getResourcesReferencedInCode();
              if (referencedResources != null) {
                writeMetadataValues(
                    buildableContext,
                    REFERENCED_RESOURCES,
                    Ordering.natural().immutableSortedCopy(referencedResources));
              }
            }

            writeMetadataValue(
                buildableContext,
                WEIGHT_ESTIMATE,
                String.valueOf(weightFactor * weightEstimate.get()));

            // Record the classnames to hashes map.
            writeMetadataValue(
                buildableContext,
                CLASSNAMES_TO_HASHES,
                ObjectMappers.WRITER.writeValueAsString(
                    Maps.transformValues(classNamesToHashes, Object::toString)));

            return StepExecutionResults.SUCCESS;
          }
        };
    steps.add(recordArtifactAndMetadataStep);

    return steps.build();
  }

  private static ImmutableSortedSet<SourcePath> getDesugarClassPaths(
      Collection<BuildRule> desugarDeps) {
    ImmutableSortedSet.Builder<SourcePath> resultBuilder = ImmutableSortedSet.naturalOrder();
    new ImmutableSortedSet.Builder<>(Ordering.natural());
    for (BuildRule rule : desugarDeps) {
      SourcePath sourcePath = rule.getSourcePathToOutput();
      if (sourcePath != null) {
        resultBuilder.add(sourcePath);
      }
    }
    return resultBuilder.build();
  }

  private static Collection<Path> getAbsolutePaths(
      Collection<SourcePath> sourcePaths, SourcePathResolver sourcePathResolver) {
    ImmutableSortedSet.Builder<Path> resultBuilder = ImmutableSortedSet.naturalOrder();
    new ImmutableSortedSet.Builder<>(Ordering.natural());
    for (SourcePath sourcePath : sourcePaths) {
      if (sourcePath != null) {
        resultBuilder.add(sourcePathResolver.getAbsolutePath(sourcePath));
      }
    }
    return resultBuilder.build();
  }

  @Override
  public BuildOutput initializeFromDisk(SourcePathResolver pathResolver) throws IOException {
    int weightEstimate =
        Integer.parseInt(
            readMetadataValue(getProjectFilesystem(), getBuildTarget(), WEIGHT_ESTIMATE).get());
    Map<String, String> map =
        ObjectMappers.readValue(
            readMetadataValue(getProjectFilesystem(), getBuildTarget(), CLASSNAMES_TO_HASHES).get(),
            new TypeReference<Map<String, String>>() {});
    Map<String, HashCode> classnamesToHashes = Maps.transformValues(map, HashCode::fromString);
    ImmutableList<String> referencedResources = readMetadataValues(REFERENCED_RESOURCES);
    return new BuildOutput(
        weightEstimate, ImmutableSortedMap.copyOf(classnamesToHashes), referencedResources);
  }

  private static Path getMetadataPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, String key) {
    return BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/metadata/" + key);
  }

  private void writeMetadataValues(
      BuildableContext buildableContext, String key, ImmutableList<String> values)
      throws IOException {
    writeMetadataValue(buildableContext, key, ObjectMappers.WRITER.writeValueAsString(values));
  }

  private void writeMetadataValue(BuildableContext buildableContext, String key, String value)
      throws IOException {
    Path path = getMetadataPath(getProjectFilesystem(), getBuildTarget(), key);
    getProjectFilesystem().mkdirs(path.getParent());
    getProjectFilesystem().writeContentsToPath(value, path);
    buildableContext.recordArtifact(path);
  }

  @VisibleForTesting
  static Optional<String> readMetadataValue(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, String key) {
    Path path = getMetadataPath(projectFilesystem, buildTarget, key);
    return projectFilesystem.readFileIfItExists(path);
  }

  private ImmutableList<String> readMetadataValues(String key) throws IOException {
    Optional<String> value = readMetadataValue(getProjectFilesystem(), getBuildTarget(), key);
    if (value.isPresent()) {
      return ObjectMappers.readValue(value.get(), new TypeReference<ImmutableList<String>>() {});
    }
    return ImmutableList.of();
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  static class BuildOutput {
    @VisibleForTesting final int weightEstimate;
    private final ImmutableSortedMap<String, HashCode> classnamesToHashes;
    private final ImmutableList<String> referencedResources;

    BuildOutput(
        int weightEstimate,
        ImmutableSortedMap<String, HashCode> classnamesToHashes,
        ImmutableList<String> referencedResources) {
      this.weightEstimate = weightEstimate;
      this.classnamesToHashes = classnamesToHashes;
      this.referencedResources = referencedResources;
    }
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    // A .dex file is not guaranteed to be generated, so we return null to be conservative.
    return null;
  }

  @Nullable
  @VisibleForTesting
  public ImmutableSortedSet<SourcePath> getDesugarDeps() {
    return desugarDeps;
  }

  public SourcePath getSourcePathToDex() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToDex());
  }

  public Path getPathToDex() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.dex.jar");
  }

  public boolean hasOutput() {
    return !getClassNames().isEmpty();
  }

  ImmutableSortedMap<String, HashCode> getClassNames() {
    return buildOutputInitializer.getBuildOutput().classnamesToHashes;
  }

  int getWeightEstimate() {
    return buildOutputInitializer.getBuildOutput().weightEstimate;
  }

  ImmutableList<String> getReferencedResources() {
    return buildOutputInitializer.getBuildOutput().referencedResources;
  }
}
