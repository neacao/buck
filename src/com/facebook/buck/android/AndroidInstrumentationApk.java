/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Apk that functions as a test package in Android.
 *
 * <p>Android's <a href="http://developer.android.com/tools/testing/testing_android.html">Testing
 * Fundamentals</a> documentation includes a diagram that shows the relationship between an
 * "application package" and a "test package" when running a test. This corresponds to a test
 * package. Note that a test package has an interesting quirk where it is <em>compiled against</em>
 * an application package, but <em>must not include</em> the resources or Java classes of the
 * application package. Therefore, this class takes responsibility for making sure the appropriate
 * bits are excluded. Failing to do so will generate mysterious runtime errors when running the
 * test.
 */
public class AndroidInstrumentationApk extends AndroidBinary {

  private AndroidBinary apkUnderTest;

  AndroidInstrumentationApk(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidSdkLocation androidSdkLocation,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      AndroidBinary apkUnderTest,
      ImmutableSet<JavaLibrary> rulesToExcludeFromDex,
      AndroidGraphEnhancementResult enhancementResult,
      DexFilesInfo dexFilesInfo,
      NativeFilesInfo nativeFilesInfo,
      ResourceFilesInfo resourceFilesInfo,
      Optional<ExopackageInfo> exopackageInfo) {
    super(
        buildTarget,
        projectFilesystem,
        androidSdkLocation,
        androidPlatformTarget,
        buildRuleParams,
        ruleFinder,
        apkUnderTest.getProguardJvmArgs(),
        apkUnderTest.getKeystore(),
        // Do not split the test apk even if the tested apk is split
        DexSplitMode.NO_SPLIT,
        apkUnderTest.getBuildTargetsToExcludeFromDex(),
        apkUnderTest.getSdkProguardConfig(),
        apkUnderTest.getOptimizationPasses(),
        apkUnderTest.getProguardConfig(),
        apkUnderTest.getSkipProguard(),
        Optional.empty(), // RedexOptions
        apkUnderTest.getResourceCompressionMode(),
        apkUnderTest.getCpuFilters(),
        apkUnderTest.getResourceFilter(),
        EnumSet.noneOf(ExopackageMode.class),
        rulesToExcludeFromDex,
        enhancementResult,
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        false,
        false,
        Optional.empty(),
        apkUnderTest.getManifestEntries(),
        apkUnderTest.getJavaRuntimeLauncher(),
        true,
        Optional.empty(),
        dexFilesInfo,
        nativeFilesInfo,
        resourceFilesInfo,
        ImmutableSortedSet.copyOf(enhancementResult.getAPKModuleGraph().getAPKModules()),
        exopackageInfo);
    this.apkUnderTest = apkUnderTest;
  }

  @Override
  public Stream<BuildTarget> getInstallHelpers() {
    return Stream.of();
  }

  public AndroidBinary getApkUnderTest() {
    return apkUnderTest;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return RichStream.of(apkUnderTest.getBuildTarget())
        .concat(super.getRuntimeDeps(buildRuleResolver));
  }
}
