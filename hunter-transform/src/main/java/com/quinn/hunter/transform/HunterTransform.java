package com.quinn.hunter.transform;

import com.android.build.api.transform.Context;
import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.quinn.hunter.transform.asm.BaseWeaver;
import com.quinn.hunter.transform.asm.ClassLoaderHelper;
import com.quinn.hunter.transform.concurrent.Schedulers;
import com.quinn.hunter.transform.concurrent.Worker;

import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
/**
 * Created by Quinn on 26/02/2017.
 * Transform to modify bytecode
 */
public class HunterTransform extends Transform {

    private final Logger logger;

    private static final Set<QualifiedContent.Scope> SCOPES = new HashSet<>();

    static {
        SCOPES.add(QualifiedContent.Scope.PROJECT);
        SCOPES.add(QualifiedContent.Scope.SUB_PROJECTS);
        SCOPES.add(QualifiedContent.Scope.EXTERNAL_LIBRARIES);
    }

    private final Project project;
    protected BaseWeaver bytecodeWeaver;
    private final Worker worker;
    private boolean emptyRun = false;

    public HunterTransform(Project project){
        this.project = project;
        this.logger = project.getLogger();
        this.worker = Schedulers.IO();
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    public Set<QualifiedContent.Scope> getScopes() {
        return SCOPES;
    }

    @Override
    public boolean isIncremental() {
        return true;
    }


    @SuppressWarnings("deprecation")
    @Override
    public void transform(Context context,
                   Collection<TransformInput> inputs,
                   Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider,
                   boolean isIncremental) throws IOException, TransformException, InterruptedException {
        RunVariant runVariant = getRunVariant();
        if("debug".equals(context.getVariantName())) {
            emptyRun = runVariant == RunVariant.RELEASE || runVariant == RunVariant.NEVER;
        } else if("release".equals(context.getVariantName())) {
            emptyRun = runVariant == RunVariant.DEBUG || runVariant == RunVariant.NEVER;
        }
        logger.warn(getName() + " isIncremental = " + isIncremental + ", runVariant = "
                + runVariant + ", emptyRun = " + emptyRun + ", inDuplicatedClassSafeMode = " + inDuplicatedClassSafeMode());
        long startTime = System.currentTimeMillis();
        if(!isIncremental) {
            outputProvider.deleteAll();
        }
        // 把所有的类加载到类加载器里面：包括 android.jar
        URLClassLoader urlClassLoader = ClassLoaderHelper.getClassLoader(inputs, referencedInputs, project);
        this.bytecodeWeaver.setClassLoader(urlClassLoader);
        boolean flagForCleanDexBuilderFolder = false;
        // 遍历所有的类，对 jar 和 文件夹里面的类进行处理
        Collection<JarInput> jarInputs;
        Collection<DirectoryInput> directoryInputs;
        for (TransformInput input : inputs) {
            // input 包含2块内容
            jarInputs = input.getJarInputs();
            directoryInputs = input.getDirectoryInputs();
            // 处理 jar
            flagForCleanDexBuilderFolder = processJar(outputProvider, isIncremental, flagForCleanDexBuilderFolder, jarInputs);
            // 处理文件夹
            processDir(outputProvider, isIncremental, directoryInputs);

        }

        worker.await();
        long costTime = System.currentTimeMillis() - startTime;
        logger.warn((getName() + " costed " + costTime + "ms"));
    }

    private void processDir(TransformOutputProvider outputProvider, boolean isIncremental, Collection<DirectoryInput> directoryInputs) throws IOException {
        for(DirectoryInput directoryInput : directoryInputs) {
            File dest = outputProvider.getContentLocation(directoryInput.getName(),
                    directoryInput.getContentTypes(), directoryInput.getScopes(),
                    Format.DIRECTORY);
            FileUtils.forceMkdir(dest);
            if(isIncremental && !emptyRun) {
                // 增量更新
                String srcDirPath = directoryInput.getFile().getAbsolutePath();
                String destDirPath = dest.getAbsolutePath();
                Map<File, Status> fileStatusMap = directoryInput.getChangedFiles();
                // 处理变动过的文件
                for (Map.Entry<File, Status> changedFile : fileStatusMap.entrySet()) {
                    Status status = changedFile.getValue();
                    File inputFile = changedFile.getKey();
                    String destFilePath = inputFile.getAbsolutePath().replace(srcDirPath, destDirPath);
                    File destFile = new File(destFilePath);
                    switch (status) {
                        case NOTCHANGED:
                            break;
                        case REMOVED:
                            if(destFile.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                destFile.delete();
                            }
                            break;
                        case ADDED:
                        case CHANGED:
                            try {
                                FileUtils.touch(destFile);
                            } catch (IOException e) {
                                //maybe mkdirs fail for some strange reason, try again.
                                FileUtils.forceMkdirParent(destFile);
                            }
                            // 单个文件处理
                            transformSingleFile(inputFile, destFile, srcDirPath);
                            break;
                    }
                }
            } else {
                transformDir(directoryInput.getFile(), dest);
            }

        }
    }

    /**
     * 处理jar 文件
     * @param outputProvider
     * @param isIncremental
     * @param flagForCleanDexBuilderFolder
     * @return
     * @throws IOException
     */
    private boolean processJar(TransformOutputProvider outputProvider, boolean isIncremental, boolean flagForCleanDexBuilderFolder, Collection<JarInput> jarInputs) throws IOException {
        for(JarInput jarInput : jarInputs) {
            Status status = jarInput.getStatus();
            File dest = outputProvider.getContentLocation(
                    jarInput.getFile().getAbsolutePath(),
                    jarInput.getContentTypes(),
                    jarInput.getScopes(),
                    Format.JAR);
            if(isIncremental && !emptyRun) {
                switch(status) {
                    case NOTCHANGED:
                        break;
                    case ADDED:
                    case CHANGED:
                        transformJar(jarInput.getFile(), dest, status);
                        break;
                    case REMOVED:
                        if (dest.exists()) {
                            FileUtils.forceDelete(dest);
                        }
                        break;
                }
            } else {
                //Forgive me!, Some project will store 3rd-party aar for several copies in dexbuilder folder,unknown issue.
                if(inDuplicatedClassSafeMode() && !isIncremental && !flagForCleanDexBuilderFolder) {
                    cleanDexBuilderFolder(dest);
                    flagForCleanDexBuilderFolder = true;
                }
                transformJar(jarInput.getFile(), dest, status);
            }
        }
        return flagForCleanDexBuilderFolder;
    }

    private void transformSingleFile(final File inputFile, final File outputFile, final String srcBaseDir) {
        worker.submit(() -> {
            bytecodeWeaver.weaveSingleClassToFile(inputFile, outputFile, srcBaseDir);
            return null;
        });
    }

    private void transformDir(final File inputDir, final File outputDir) throws IOException {
        if(emptyRun) {
            FileUtils.copyDirectory(inputDir, outputDir);
            return;
        }
        final String inputDirPath = inputDir.getAbsolutePath();
        final String outputDirPath = outputDir.getAbsolutePath();
        if (inputDir.isDirectory()) {
            for (final File file : com.android.utils.FileUtils.getAllFiles(inputDir)) {
                worker.submit(() -> {
                    String filePath = file.getAbsolutePath();
                    File outputFile = new File(filePath.replace(inputDirPath, outputDirPath));
                    bytecodeWeaver.weaveSingleClassToFile(file, outputFile, inputDirPath);
                    return null;
                });
            }
        }
    }

    private void transformJar(final File srcJar, final File destJar, Status status) {
        worker.submit(() -> {
            if(emptyRun) {
                FileUtils.copyFile(srcJar, destJar);
                return null;
            }
            bytecodeWeaver.weaveJar(srcJar, destJar);
            return null;
        });
    }

    private void cleanDexBuilderFolder(File dest) {
        worker.submit(() -> {
            try {
                String dexBuilderDir = replaceLastPart(dest.getAbsolutePath(), getName(), "dexBuilder");
                //intermediates/transforms/dexBuilder/debug
                File file = new File(dexBuilderDir).getParentFile();
                project.getLogger().warn("clean dexBuilder folder = " + file.getAbsolutePath());
                if(file.exists() && file.isDirectory()) {
                    com.android.utils.FileUtils.deleteDirectoryContents(file);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    private String replaceLastPart(String originString, String replacement, String toreplace) {
        int start = originString.lastIndexOf(replacement);
        StringBuilder builder = new StringBuilder();
        builder.append(originString, 0, start);
        builder.append(toreplace);
        builder.append(originString.substring(start + replacement.length()));
        return builder.toString();
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    protected RunVariant getRunVariant() {
        return RunVariant.ALWAYS;
    }

    protected boolean inDuplicatedClassSafeMode(){
        return false;
    }
}
