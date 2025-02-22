/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.DefaultFileSystemLocation;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.internal.provider.Providers;
import org.gradle.api.provider.Provider;
import org.gradle.internal.Try;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.execution.DeferredExecutionHandler;
import org.gradle.internal.execution.ExecutionEngine;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.caching.CachingDisabledReason;
import org.gradle.internal.execution.caching.CachingDisabledReasonCategory;
import org.gradle.internal.execution.fingerprint.FileCollectionFingerprinter;
import org.gradle.internal.execution.fingerprint.InputFingerprinter;
import org.gradle.internal.execution.fingerprint.InputFingerprinter.FileValueSupplier;
import org.gradle.internal.execution.fingerprint.InputFingerprinter.InputVisitor;
import org.gradle.internal.execution.fingerprint.impl.DefaultFileNormalizationSpec;
import org.gradle.internal.execution.history.OverlappingOutputs;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.internal.execution.workspace.WorkspaceProvider;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.gradle.internal.vfs.FileSystemAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.gradle.internal.execution.fingerprint.InputFingerprinter.InputPropertyType.NON_INCREMENTAL;
import static org.gradle.internal.execution.fingerprint.InputFingerprinter.InputPropertyType.PRIMARY;
import static org.gradle.internal.file.TreeType.DIRECTORY;
import static org.gradle.internal.file.TreeType.FILE;

public class DefaultTransformerInvocationFactory implements TransformerInvocationFactory {
    private static final CachingDisabledReason NOT_CACHEABLE = new CachingDisabledReason(CachingDisabledReasonCategory.NOT_CACHEABLE, "Caching not enabled.");
    private static final String INPUT_ARTIFACT_PROPERTY_NAME = "inputArtifact";
    private static final String INPUT_ARTIFACT_PATH_PROPERTY_NAME = "inputArtifactPath";
    private static final String INPUT_ARTIFACT_SNAPSHOT_PROPERTY_NAME = "inputArtifactSnapshot";
    private static final String DEPENDENCIES_PROPERTY_NAME = "inputArtifactDependencies";
    private static final String SECONDARY_INPUTS_HASH_PROPERTY_NAME = "inputPropertiesHash";
    private static final String OUTPUT_DIRECTORY_PROPERTY_NAME = "outputDirectory";
    private static final String RESULTS_FILE_PROPERTY_NAME = "resultsFile";
    private static final String INPUT_FILE_PATH_PREFIX = "i/";
    private static final String OUTPUT_FILE_PATH_PREFIX = "o/";

    private final ExecutionEngine executionEngine;
    private final FileSystemAccess fileSystemAccess;
    private final ArtifactTransformListener artifactTransformListener;
    private final TransformationWorkspaceServices immutableWorkspaceProvider;
    private final FileCollectionFactory fileCollectionFactory;
    private final ProjectStateRegistry projectStateRegistry;
    private final BuildOperationExecutor buildOperationExecutor;

    public DefaultTransformerInvocationFactory(
        ExecutionEngine executionEngine,
        FileSystemAccess fileSystemAccess,
        ArtifactTransformListener artifactTransformListener,
        TransformationWorkspaceServices immutableWorkspaceProvider,
        FileCollectionFactory fileCollectionFactory,
        ProjectStateRegistry projectStateRegistry,
        BuildOperationExecutor buildOperationExecutor
    ) {
        this.executionEngine = executionEngine;
        this.fileSystemAccess = fileSystemAccess;
        this.artifactTransformListener = artifactTransformListener;
        this.immutableWorkspaceProvider = immutableWorkspaceProvider;
        this.fileCollectionFactory = fileCollectionFactory;
        this.projectStateRegistry = projectStateRegistry;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public CacheableInvocation<ImmutableList<File>> createInvocation(
        Transformer transformer,
        File inputArtifact,
        ArtifactTransformDependencies dependencies,
        TransformationSubject subject,
        InputFingerprinter inputFingerprinter
    ) {
        ProjectInternal producerProject = determineProducerProject(subject);
        TransformationWorkspaceServices workspaceServices = determineWorkspaceServices(producerProject);

        UnitOfWork execution;
        if (producerProject == null) {
            execution = new ImmutableTransformerExecution(
                transformer,
                inputArtifact,
                dependencies,
                buildOperationExecutor,
                fileCollectionFactory,
                inputFingerprinter,
                fileSystemAccess,
                workspaceServices
            );
        } else {
            execution = new MutableTransformerExecution(
                transformer,
                inputArtifact,
                dependencies,
                buildOperationExecutor,
                fileCollectionFactory,
                inputFingerprinter,
                workspaceServices
            );
        }

        return executionEngine.createRequest(execution)
            .withIdentityCache(workspaceServices.getIdentityCache())
            .getOrDeferExecution(new DeferredExecutionHandler<ImmutableList<File>, CacheableInvocation<ImmutableList<File>>>() {
                @Override
                public CacheableInvocation<ImmutableList<File>> processCachedOutput(Try<ImmutableList<File>> cachedOutput) {
                    return CacheableInvocation.cached(mapResult(cachedOutput));
                }

                @Override
                public CacheableInvocation<ImmutableList<File>> processDeferredOutput(Supplier<Try<ImmutableList<File>>> deferredExecution) {
                    return CacheableInvocation.nonCached(() ->
                        fireTransformListeners(transformer, subject, () ->
                            mapResult(deferredExecution.get())));
                }

                @Nonnull
                private Try<ImmutableList<File>> mapResult(Try<ImmutableList<File>> cachedOutput) {
                    return cachedOutput
                        .mapFailure(failure -> new TransformException(String.format("Execution failed for %s.", execution.getDisplayName()), failure));
                }
            });
    }

    private TransformationWorkspaceServices determineWorkspaceServices(@Nullable ProjectInternal producerProject) {
        if (producerProject == null) {
            return immutableWorkspaceProvider;
        }
        return producerProject.getServices().get(TransformationWorkspaceServices.class);
    }

    @Nullable
    private ProjectInternal determineProducerProject(TransformationSubject subject) {
        ComponentIdentifier componentIdentifier = subject.getInitialComponentIdentifier();
        if (componentIdentifier instanceof ProjectComponentIdentifier) {
            return projectStateRegistry.stateFor((ProjectComponentIdentifier) componentIdentifier).getMutableModel();
        } else {
            return null;
        }
    }

    private <T> T fireTransformListeners(Transformer transformer, TransformationSubject subject, Supplier<T> execution) {
        artifactTransformListener.beforeTransformerInvocation(transformer, subject);
        try {
            return execution.get();
        } finally {
            artifactTransformListener.afterTransformerInvocation(transformer, subject);
        }
    }

    private static class ImmutableTransformerExecution extends AbstractTransformerExecution {
        private final FileSystemAccess fileSystemAccess;

        public ImmutableTransformerExecution(
            Transformer transformer,
            File inputArtifact,
            ArtifactTransformDependencies dependencies,
            BuildOperationExecutor buildOperationExecutor,
            FileCollectionFactory fileCollectionFactory,
            InputFingerprinter inputFingerprinter,
            FileSystemAccess fileSystemAccess,
            TransformationWorkspaceServices workspaceServices
        ) {
            super(transformer, inputArtifact, dependencies, buildOperationExecutor, fileCollectionFactory, inputFingerprinter, workspaceServices);
            this.fileSystemAccess = fileSystemAccess;
        }

        @Override
        public void visitIdentityInputs(InputVisitor visitor) {
            super.visitIdentityInputs(visitor);
            // This is a performance hack. We could use the regular fingerprint of the input artifact, but that takes longer than
            // capturing the normalized path and the snapshot of the raw contents, so we are using these to determine the identity
            FileSystemLocationSnapshot inputArtifactSnapshot = fileSystemAccess.read(inputArtifact.getAbsolutePath(), Function.identity());
            visitor.visitInputProperty(INPUT_ARTIFACT_PATH_PROPERTY_NAME, () -> {
                FileCollectionFingerprinter inputArtifactFingerprinter = inputFingerprinter.getFingerprinterRegistry().getFingerprinter(
                    DefaultFileNormalizationSpec.from(transformer.getInputArtifactNormalizer(), transformer.getInputArtifactDirectorySensitivity()));
                return inputArtifactFingerprinter.normalizePath(inputArtifactSnapshot);
            });
            visitor.visitInputProperty(INPUT_ARTIFACT_SNAPSHOT_PROPERTY_NAME, inputArtifactSnapshot::getHash);
        }

        @Override
        public Identity identify(Map<String, ValueSnapshot> identityInputs, Map<String, CurrentFileCollectionFingerprint> identityFileInputs) {
            return new ImmutableTransformationWorkspaceIdentity(
                identityInputs.get(INPUT_ARTIFACT_PATH_PROPERTY_NAME),
                identityInputs.get(INPUT_ARTIFACT_SNAPSHOT_PROPERTY_NAME),
                identityInputs.get(SECONDARY_INPUTS_HASH_PROPERTY_NAME),
                identityFileInputs.get(DEPENDENCIES_PROPERTY_NAME).getHash()
            );
        }
    }

    private static class MutableTransformerExecution extends AbstractTransformerExecution {
        public MutableTransformerExecution(
            Transformer transformer,
            File inputArtifact,
            ArtifactTransformDependencies dependencies,
            BuildOperationExecutor buildOperationExecutor,
            FileCollectionFactory fileCollectionFactory,
            InputFingerprinter inputFingerprinter,
            TransformationWorkspaceServices workspaceServices
        ) {
            super(transformer, inputArtifact, dependencies, buildOperationExecutor, fileCollectionFactory, inputFingerprinter, workspaceServices);
        }

        @Override
        public Identity identify(Map<String, ValueSnapshot> identityInputs, Map<String, CurrentFileCollectionFingerprint> identityFileInputs) {
            return new MutableTransformationWorkspaceIdentity(
                inputArtifact.getAbsolutePath(),
                identityInputs.get(SECONDARY_INPUTS_HASH_PROPERTY_NAME),
                identityFileInputs.get(DEPENDENCIES_PROPERTY_NAME).getHash()
            );
        }
    }

    private abstract static class AbstractTransformerExecution implements UnitOfWork {
        protected final Transformer transformer;
        protected final File inputArtifact;
        private final ArtifactTransformDependencies dependencies;

        private final BuildOperationExecutor buildOperationExecutor;
        private final FileCollectionFactory fileCollectionFactory;

        private final Timer executionTimer;
        private final Provider<FileSystemLocation> inputArtifactProvider;
        protected final InputFingerprinter inputFingerprinter;
        private final TransformationWorkspaceServices workspaceServices;

        public AbstractTransformerExecution(
            Transformer transformer,
            File inputArtifact,
            ArtifactTransformDependencies dependencies,

            BuildOperationExecutor buildOperationExecutor,
            FileCollectionFactory fileCollectionFactory,
            InputFingerprinter inputFingerprinter,
            TransformationWorkspaceServices workspaceServices
        ) {
            this.transformer = transformer;
            this.inputArtifact = inputArtifact;
            this.dependencies = dependencies;
            this.executionTimer = Time.startTimer();
            this.inputArtifactProvider = Providers.of(new DefaultFileSystemLocation(inputArtifact));

            this.buildOperationExecutor = buildOperationExecutor;
            this.fileCollectionFactory = fileCollectionFactory;
            this.inputFingerprinter = inputFingerprinter;
            this.workspaceServices = workspaceServices;
        }

        @Override
        public WorkOutput execute(ExecutionRequest executionRequest) {
            ImmutableList<File> result = buildOperationExecutor.call(new CallableBuildOperation<ImmutableList<File>>() {
                @Override
                public ImmutableList<File> call(BuildOperationContext context) {
                    File workspace = executionRequest.getWorkspace();
                    InputChangesInternal inputChanges = executionRequest.getInputChanges().orElse(null);
                    ImmutableList<File> result = transformer.transform(inputArtifactProvider, getOutputDir(workspace), dependencies, inputChanges);
                    writeResultsFile(workspace, result);
                    return result;
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    String displayName = transformer.getDisplayName() + " " + inputArtifact.getName();
                    return BuildOperationDescriptor.displayName(displayName)
                        .progressDisplayName(displayName);
                }
            });

            return new WorkOutput() {
                @Override
                public WorkResult getDidWork() {
                    return WorkResult.DID_WORK;
                }

                @Override
                public Object getOutput() {
                    return result;
                }
            };
        }

        @Override
        public Object loadRestoredOutput(File workspace) {
            return readResultsFile(workspace);
        }

        @Override
        public WorkspaceProvider getWorkspaceProvider() {
            return workspaceServices.getWorkspaceProvider();
        }

        @Override
        public InputFingerprinter getInputFingerprinter() {
            return inputFingerprinter;
        }

        private void writeResultsFile(File workspace, ImmutableList<File> result) {
            File outputDir = getOutputDir(workspace);
            String outputDirPrefix = outputDir.getPath() + File.separator;
            String inputFilePrefix = inputArtifact.getPath() + File.separator;
            Stream<String> relativePaths = result.stream().map(file -> {
                if (file.equals(outputDir)) {
                    return OUTPUT_FILE_PATH_PREFIX;
                }
                if (file.equals(inputArtifact)) {
                    return INPUT_FILE_PATH_PREFIX;
                }
                String absolutePath = file.getAbsolutePath();
                if (absolutePath.startsWith(outputDirPrefix)) {
                    return OUTPUT_FILE_PATH_PREFIX + RelativePath.parse(true, absolutePath.substring(outputDirPrefix.length())).getPathString();
                }
                if (absolutePath.startsWith(inputFilePrefix)) {
                    return INPUT_FILE_PATH_PREFIX + RelativePath.parse(true, absolutePath.substring(inputFilePrefix.length())).getPathString();
                }
                throw new IllegalStateException("Invalid result path: " + absolutePath);
            });
            UncheckedException.callUnchecked(() -> Files.write(getResultsFile(workspace).toPath(), (Iterable<String>) relativePaths::iterator));
        }

        private ImmutableList<File> readResultsFile(File workspace) {
            Path transformerResultsPath = getResultsFile(workspace).toPath();
            try {
                ImmutableList.Builder<File> builder = ImmutableList.builder();
                List<String> paths = Files.readAllLines(transformerResultsPath, StandardCharsets.UTF_8);
                for (String path : paths) {
                    if (path.startsWith(OUTPUT_FILE_PATH_PREFIX)) {
                        builder.add(new File(getOutputDir(workspace), path.substring(2)));
                    } else if (path.startsWith(INPUT_FILE_PATH_PREFIX)) {
                        builder.add(new File(inputArtifact, path.substring(2)));
                    } else {
                        throw new IllegalStateException("Cannot parse result path string: " + path);
                    }
                }
                return builder.build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static File getOutputDir(File workspace) {
            return new File(workspace, "transformed");
        }

        private static File getResultsFile(File workspace) {
            return new File(workspace, "results.bin");
        }

        @Override
        public Optional<Duration> getTimeout() {
            return Optional.empty();
        }

        @Override
        public InputChangeTrackingStrategy getInputChangeTrackingStrategy() {
            return transformer.requiresInputChanges() ? InputChangeTrackingStrategy.INCREMENTAL_PARAMETERS : InputChangeTrackingStrategy.NONE;
        }

        @Override
        public void visitImplementations(ImplementationVisitor visitor) {
            visitor.visitImplementation(transformer.getImplementationClass());
        }

        @Override
        @OverridingMethodsMustInvokeSuper
        public void visitIdentityInputs(InputVisitor visitor) {
            // Emulate secondary inputs as a single property for now
            visitor.visitInputProperty(SECONDARY_INPUTS_HASH_PROPERTY_NAME, transformer::getSecondaryInputHash);
            visitor.visitInputFileProperty(DEPENDENCIES_PROPERTY_NAME, NON_INCREMENTAL,
                new FileValueSupplier(
                    dependencies,
                    transformer.getInputArtifactDependenciesNormalizer(),
                    transformer.getInputArtifactDependenciesDirectorySensitivity(),
                    () -> dependencies.getFiles()
                        .orElse(fileCollectionFactory.empty())));
        }

        @Override
        @OverridingMethodsMustInvokeSuper
        public void visitRegularInputs(InputVisitor visitor) {
            visitor.visitInputFileProperty(INPUT_ARTIFACT_PROPERTY_NAME, PRIMARY,
                new FileValueSupplier(
                    inputArtifactProvider,
                    transformer.getInputArtifactNormalizer(),
                    transformer.getInputArtifactDirectorySensitivity(),
                    () -> fileCollectionFactory.fixed(inputArtifact)));
        }

        @Override
        public void visitOutputs(File workspace, OutputVisitor visitor) {
            File outputDir = getOutputDir(workspace);
            File resultsFile = getResultsFile(workspace);
            visitor.visitOutputProperty(OUTPUT_DIRECTORY_PROPERTY_NAME, DIRECTORY,
                outputDir,
                fileCollectionFactory.fixed(outputDir));
            visitor.visitOutputProperty(RESULTS_FILE_PROPERTY_NAME, FILE,
                resultsFile,
                fileCollectionFactory.fixed(resultsFile));
        }

        @Override
        public long markExecutionTime() {
            return executionTimer.getElapsedMillis();
        }

        @Override
        public Optional<CachingDisabledReason> shouldDisableCaching(@Nullable OverlappingOutputs detectedOverlappingOutputs) {
            return transformer.isCacheable()
                ? Optional.empty()
                : Optional.of(NOT_CACHEABLE);
        }

        @Override
        public String getDisplayName() {
            return transformer.getDisplayName() + ": " + inputArtifact;
        }
    }

    private static class ImmutableTransformationWorkspaceIdentity implements UnitOfWork.Identity {
        private final ValueSnapshot inputArtifactPath;
        private final ValueSnapshot inputArtifactSnapshot;
        private final ValueSnapshot secondaryInputSnapshot;
        private final HashCode dependenciesHash;

        public ImmutableTransformationWorkspaceIdentity(ValueSnapshot inputArtifactPath, ValueSnapshot inputArtifactSnapshot, ValueSnapshot secondaryInputSnapshot, HashCode dependenciesHash) {
            this.inputArtifactPath = inputArtifactPath;
            this.inputArtifactSnapshot = inputArtifactSnapshot;
            this.secondaryInputSnapshot = secondaryInputSnapshot;
            this.dependenciesHash = dependenciesHash;
        }

        @Override
        public String getUniqueId() {
            Hasher hasher = Hashing.newHasher();
            inputArtifactPath.appendToHasher(hasher);
            inputArtifactSnapshot.appendToHasher(hasher);
            secondaryInputSnapshot.appendToHasher(hasher);
            hasher.putHash(dependenciesHash);
            return hasher.hash().toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ImmutableTransformationWorkspaceIdentity that = (ImmutableTransformationWorkspaceIdentity) o;

            if (!inputArtifactPath.equals(that.inputArtifactPath)) {
                return false;
            }
            if (!inputArtifactSnapshot.equals(that.inputArtifactSnapshot)) {
                return false;
            }
            if (!secondaryInputSnapshot.equals(that.secondaryInputSnapshot)) {
                return false;
            }
            return dependenciesHash.equals(that.dependenciesHash);
        }

        @Override
        public int hashCode() {
            int result = inputArtifactPath.hashCode();
            result = 31 * result + inputArtifactSnapshot.hashCode();
            result = 31 * result + secondaryInputSnapshot.hashCode();
            result = 31 * result + dependenciesHash.hashCode();
            return result;
        }
    }

    public static class MutableTransformationWorkspaceIdentity implements UnitOfWork.Identity {
        private final String inputArtifactAbsolutePath;
        private final ValueSnapshot secondaryInputsSnapshot;
        private final HashCode dependenciesHash;

        public MutableTransformationWorkspaceIdentity(String inputArtifactAbsolutePath, ValueSnapshot secondaryInputsSnapshot, HashCode dependenciesHash) {
            this.inputArtifactAbsolutePath = inputArtifactAbsolutePath;
            this.secondaryInputsSnapshot = secondaryInputsSnapshot;
            this.dependenciesHash = dependenciesHash;
        }

        @Override
        public String getUniqueId() {
            Hasher hasher = Hashing.newHasher();
            hasher.putString(inputArtifactAbsolutePath);
            secondaryInputsSnapshot.appendToHasher(hasher);
            hasher.putHash(dependenciesHash);
            return hasher.hash().toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            MutableTransformationWorkspaceIdentity that = (MutableTransformationWorkspaceIdentity) o;

            if (!secondaryInputsSnapshot.equals(that.secondaryInputsSnapshot)) {
                return false;
            }
            if (!dependenciesHash.equals(that.dependenciesHash)) {
                return false;
            }
            return inputArtifactAbsolutePath.equals(that.inputArtifactAbsolutePath);
        }

        @Override
        public int hashCode() {
            int result = inputArtifactAbsolutePath.hashCode();
            result = 31 * result + secondaryInputsSnapshot.hashCode();
            result = 31 * result + dependenciesHash.hashCode();
            return result;
        }
    }
}
