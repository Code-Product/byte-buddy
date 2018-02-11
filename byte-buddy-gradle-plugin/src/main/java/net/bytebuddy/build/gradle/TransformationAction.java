package net.bytebuddy.build.gradle;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.build.EntryPoint;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer;
import net.bytebuddy.implementation.LoadedTypeInitializer;
import net.bytebuddy.pool.TypePool;
import org.gradle.api.*;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.AbstractCompile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Applies a transformation to the classes that were generated by a compilation task.
 */
public class TransformationAction implements Action<Task> {

    /**
     * The class file extension for Java files.
     */
    private static final String CLASS_FILE_EXTENSION = ".class";

    /**
     * The current project.
     */
    private final Project project;

    /**
     * The current project's Byte Buddy extension.
     */
    private final ByteBuddyExtension byteBuddyExtension;

    /**
     * The task to which this transformation was appended.
     */
    private final AbstractCompile task;

    /**
     * Creates a new transformation action.
     *
     * @param project   The current project.
     * @param extension The current project's Byte Buddy extension.
     * @param task      The task to which this transformation was appended.
     */
    public TransformationAction(Project project, ByteBuddyExtension extension, AbstractCompile task) {
        this.project = project;
        this.byteBuddyExtension = extension;
        this.task = task;
    }

    @Override
    public void execute(Task task) {
        try {
            processOutputDirectory(this.task.getDestinationDir(), this.task.getClasspath());
        } catch (IOException exception) {
            throw new GradleException("Error accessing file system", exception);
        }
    }

    /**
     * Processes all class files within the given directory.
     *
     * @param root      The root directory to process.
     * @param classPath A list of class path elements expected by the processed classes.
     * @throws IOException If an I/O exception occurs.
     */
    private void processOutputDirectory(File root, Iterable<? extends File> classPath) throws IOException {
        if (!root.isDirectory()) {
            throw new GradleException("Target location does not exist or is no directory: " + root);
        }
        ClassLoaderResolver classLoaderResolver = new ClassLoaderResolver();
        try {
            List<Plugin> plugins = new ArrayList<Plugin>(byteBuddyExtension.getTransformations().size());
            for (Transformation transformation : byteBuddyExtension.getTransformations()) {
                String plugin = transformation.getPlugin();
                try {
                    plugins.add((Plugin) Class.forName(plugin, false, classLoaderResolver.resolve(transformation.getClassPath(root, classPath)))
                            .getDeclaredConstructor()
                            .newInstance());
                    project.getLogger().info("Created plugin: {}", plugin);
                } catch (Exception exception) {
                    if (exception instanceof GradleException) {
                        throw (GradleException) exception;
                    }
                    throw new GradleException("Cannot create plugin: " + transformation.getRawPlugin(), exception);
                }
            }
            EntryPoint entryPoint = byteBuddyExtension.getInitialization().getEntryPoint(classLoaderResolver, root, classPath);
            project.getLogger().info("Resolved entry point: {}", entryPoint);
            transform(root, classPath, entryPoint, plugins);
        } finally {
            classLoaderResolver.close();
        }
    }

    /**
     * Applies all registered transformations.
     *
     * @param root       The root directory to process.
     * @param entryPoint The transformation's entry point.
     * @param classPath  A list of class path elements expected by the processed classes.
     * @param plugins    The plugins to apply.
     * @throws IOException If an I/O exception occurs.
     */
    private void transform(File root, Iterable<? extends File> classPath, EntryPoint entryPoint, List<Plugin> plugins) throws IOException {
        List<ClassFileLocator> classFileLocators = new ArrayList<ClassFileLocator>();
        classFileLocators.add(new ClassFileLocator.ForFolder(root));
        for (File artifact : classPath) {
            classFileLocators.add(artifact.isFile()
                    ? ClassFileLocator.ForJarFile.of(artifact)
                    : new ClassFileLocator.ForFolder(artifact));
        }
        ClassFileLocator classFileLocator = new ClassFileLocator.Compound(classFileLocators);
        try {
            TypePool typePool = new TypePool.Default.WithLazyResolution(new TypePool.CacheProvider.Simple(),
                    classFileLocator,
                    TypePool.Default.ReaderMode.FAST,
                    TypePool.ClassLoading.ofBootPath());
            project.getLogger().info("Processing class files located in in: {}", root);
            JavaPluginConvention convention = (JavaPluginConvention) project.getConvention().getPlugins().get("java");
            ByteBuddy byteBuddy;
            try {
                byteBuddy = entryPoint.byteBuddy(convention == null
                        ? ClassFileVersion.ofThisVm()
                        : ClassFileVersion.ofJavaVersion(Integer.parseInt(convention.getTargetCompatibility().getMajorVersion())));
            } catch (Throwable throwable) {
                throw new GradleException("Cannot create Byte Buddy instance", throwable);
            }
            processDirectory(root,
                    root,
                    byteBuddy,
                    entryPoint,
                    byteBuddyExtension.getMethodNameTransformer(),
                    classFileLocator,
                    typePool,
                    plugins);
        } finally {
            classFileLocator.close();
        }
    }

    /**
     * Processes a directory.
     *
     * @param root                  The root directory to process.
     * @param folder                The currently processed folder.
     * @param byteBuddy             The Byte Buddy instance to use.
     * @param entryPoint            The transformation's entry point.
     * @param methodNameTransformer The method name transformer to use.
     * @param classFileLocator      The class file locator to use.
     * @param typePool              The type pool to query for type descriptions.
     * @param plugins               The plugins to apply.
     */
    private void processDirectory(File root,
                                  File folder,
                                  ByteBuddy byteBuddy,
                                  EntryPoint entryPoint,
                                  MethodNameTransformer methodNameTransformer,
                                  ClassFileLocator classFileLocator,
                                  TypePool typePool,
                                  List<Plugin> plugins) {
        File[] file = folder.listFiles();
        if (file != null) {
            for (File aFile : file) {
                if (aFile.isDirectory()) {
                    processDirectory(root, aFile, byteBuddy, entryPoint, methodNameTransformer, classFileLocator, typePool, plugins);
                } else if (aFile.isFile() && aFile.getName().endsWith(CLASS_FILE_EXTENSION)) {
                    processClassFile(root,
                            root.toURI().relativize(aFile.toURI()).toString(),
                            byteBuddy,
                            entryPoint,
                            methodNameTransformer,
                            classFileLocator,
                            typePool,
                            plugins);
                } else {
                    project.getLogger().debug("Skipping ignored file: {}", aFile);
                }
            }
        }
    }

    /**
     * Processes a class file.
     *
     * @param root                  The root directory to process.
     * @param file                  The class file to process.
     * @param byteBuddy             The Byte Buddy instance to use.
     * @param entryPoint            The transformation's entry point.
     * @param methodNameTransformer The method name transformer to use.
     * @param classFileLocator      The class file locator to use.
     * @param typePool              The type pool to query for type descriptions.
     * @param plugins               The plugins to apply.
     */
    private void processClassFile(File root,
                                  String file,
                                  ByteBuddy byteBuddy,
                                  EntryPoint entryPoint,
                                  MethodNameTransformer methodNameTransformer,
                                  ClassFileLocator classFileLocator,
                                  TypePool typePool,
                                  List<Plugin> plugins) {
        String typeName = file.replace('/', '.').substring(0, file.length() - CLASS_FILE_EXTENSION.length());
        project.getLogger().debug("Processing class file: {}", typeName);
        TypeDescription typeDescription = typePool.describe(typeName).resolve();
        DynamicType.Builder<?> builder;
        try {
            builder = entryPoint.transform(typeDescription, byteBuddy, classFileLocator, methodNameTransformer);
        } catch (Throwable throwable) {
            throw new GradleException("Cannot transform type: " + typeName, throwable);
        }
        boolean transformed = false, failed = false;
        for (Plugin plugin : plugins) {
            try {
                if (plugin.matches(typeDescription)) {
                    try {
                        builder = plugin.apply(builder, typeDescription);
                        transformed = true;
                    } catch (RuntimeException exception) {
                        if (byteBuddyExtension.isFailFast()) {
                            throw exception;
                        } else {
                            project.getLogger().error("Failure during the application of {}", plugin, exception);
                            failed = true;
                        }
                    }
                }
            } catch (Throwable throwable) {
                throw new GradleException("Cannot apply " + plugin + " on " + typeName, throwable);
            }
        }
        if (failed) {
            throw new GradleException("At least one plugin failed its execution");
        } else if (transformed) {
            project.getLogger().info("Transformed type: {}", typeName);
            DynamicType dynamicType = builder.make();
            for (Map.Entry<TypeDescription, LoadedTypeInitializer> entry : dynamicType.getLoadedTypeInitializers().entrySet()) {
                if (byteBuddyExtension.isFailOnLiveInitializer() && entry.getValue().isAlive()) {
                    throw new GradleException("Cannot apply live initializer for " + entry.getKey());
                }
            }
            try {
                dynamicType.saveIn(root);
            } catch (IOException exception) {
                throw new GradleException("Cannot save " + typeName + " in " + root, exception);
            }
        } else {
            project.getLogger().debug("Skipping non-transformed type: {}", typeName);
        }
    }
}
