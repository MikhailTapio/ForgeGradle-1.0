package net.minecraftforge.gradle.dev;

//import edu.sc.seis.launch4j.Launch4jPluginExtension;

import groovy.lang.Closure;
import net.minecraftforge.gradle.CopyInto;
import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedBase;
import net.minecraftforge.gradle.tasks.DecompileTask;
import net.minecraftforge.gradle.tasks.PatchJarTask;
import net.minecraftforge.gradle.tasks.ProcessJarTask;
import net.minecraftforge.gradle.tasks.abstractutil.DelayedJar;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.abstractutil.FileFilterTask;
import net.minecraftforge.gradle.tasks.dev.*;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class FmlDevPlugin extends DevBasePlugin {
    @Override
    public void applyPlugin() {
        super.applyPlugin();

        // set fmlDir
        getExtension().setFmlDir(".");

        //configureLaunch4J();
        createJarProcessTasks();
        createProjectTasks();
        createEclipseTasks();
        createMiscTasks();
        createSourceCopyTasks();
        createPackageTasks();

        // the master setup task.
        Task task = makeTask("setupFML", DefaultTask.class);
        task.dependsOn("extractFmlSources", "generateProjects", "eclipse", "copyAssets");
        task.setGroup("FML");

        // the master task.
        task = makeTask("buildPackages");
        task.dependsOn("launch4j", "createChangelog", "packageUniversal", "packageInstaller", "packageUserDev", "packageSrc", "genJavadocs");
        task.setGroup("FML");
    }

    protected void createJarProcessTasks() {

        ProcessJarTask task2 = makeTask("deobfuscateJar", ProcessJarTask.class);
        {
            task2.setInJar(delayedFile(Constants.JAR_MERGED));
            task2.setOutCleanJar(delayedFile(DevConstants.JAR_SRG_FML));
            task2.setSrg(delayedFile(DevConstants.PACKAGED_SRG));
            task2.setExceptorCfg(delayedFile(DevConstants.PACKAGED_EXC));
            task2.addTransformer(delayedFile(DevConstants.FML_RESOURCES + "/fml_at.cfg"));
            task2.dependsOn("downloadMcpTools", "fixMappings", "mergeJars");
        }

        DecompileTask task3 = makeTask("decompile", DecompileTask.class);
        {
            task3.setInJar(delayedFile(DevConstants.JAR_SRG_FML));
            task3.setOutJar(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task3.setFernFlower(delayedFile(Constants.FERNFLOWER));
            task3.setPatch(delayedFile(DevConstants.PACKAGED_PATCH));
            task3.setAstyleConfig(delayedFile(DevConstants.ASTYLE_CFG));
            task3.dependsOn("downloadMcpTools", "deobfuscateJar", "fixMappings");
        }

        PatchJarTask task4 = makeTask("fmlPatchJar", PatchJarTask.class);
        {
            task4.setInJar(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task4.setOutJar(delayedFile(DevConstants.ZIP_PATCHED_FML));
            task4.setInPatches(delayedFile(DevConstants.FML_PATCH_DIR));
            task4.setDoesCache(false);
            task4.dependsOn("decompile");
        }
    }

    private void createSourceCopyTasks() {

        ExtractTask task = makeTask("extractMcResources", ExtractTask.class);
        {
            task.exclude(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task.into(delayedFile(DevConstants.ECLIPSE_CLEAN + "/src/main/resources"));
            task.dependsOn("extractWorkspace", "decompile");
        }

        Copy copy = makeTask("copyStart", Copy.class);
        {
            copy.from(delayedFile("{MAPPINGS_DIR}/patches"));
            copy.include("Start.java");
            copy.into(delayedFile(DevConstants.ECLIPSE_CLEAN + "/src/main/java"));
            copy.dependsOn("extractMcResources");
        }

        task = makeTask("extractMcSource", ExtractTask.class);
        {
            task.include(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(DevConstants.ZIP_DECOMP_FML));
            task.into(delayedFile(DevConstants.ECLIPSE_CLEAN + "/src/main/java"));
            task.dependsOn("copyStart");
        }

        task = makeTask("extractFmlResources", ExtractTask.class);
        {
            task.exclude(JAVA_FILES);
            task.from(delayedFile(DevConstants.ZIP_PATCHED_FML));
            task.into(delayedFile(DevConstants.ECLIPSE_FML + "/src/resources"));
            task.dependsOn("fmlPatchJar", "extractWorkspace");
        }

        copy = makeTask("copyDeobfData", Copy.class);
        {
            copy.from(delayedFile(DevConstants.DEOBF_DATA));
            copy.from(delayedFile(DevConstants.FML_VERSIONF));
            copy.into(delayedFile(DevConstants.ECLIPSE_FML + "/src/resources"));
            copy.dependsOn("extractFmlResources", "compressDeobfData");
        }

        task = makeTask("extractFmlSources", ExtractTask.class);
        {
            task.include(JAVA_FILES);
            task.exclude("cpw/**");
            task.from(delayedFile(DevConstants.ZIP_PATCHED_FML));
            task.into(delayedFile(DevConstants.ECLIPSE_FML + "/src/minecraft"));
            task.dependsOn("copyDeobfData");
        }

    }

    private void createProjectTasks() {
        GenDevProjectsTask task = makeTask("generateProjectClean", GenDevProjectsTask.class);
        {
            task.setTargetDir(delayedFile(DevConstants.ECLIPSE_CLEAN));
            task.setJson(delayedFile(DevConstants.JSON_DEV)); // Change to FmlConstants.JSON_BASE eventually, so that it's the base vanilla json
            task.dependsOn("extractNatives");
        }

        task = makeTask("generateProjectFML", GenDevProjectsTask.class);
        {
            task.setJson(delayedFile(DevConstants.JSON_DEV));
            task.setTargetDir(delayedFile(DevConstants.ECLIPSE_FML));

            task.addSource(delayedFile(DevConstants.ECLIPSE_FML + "/src/minecraft"));
            task.addSource(delayedFile(DevConstants.FML_SOURCES));

            task.addResource(delayedFile(DevConstants.ECLIPSE_FML + "/src/resources"));
            task.addResource(delayedFile(DevConstants.FML_RESOURCES));

            task.dependsOn("extractNatives", "createVersionProperties");
        }

        makeTask("generateProjects").dependsOn("generateProjectClean", "generateProjectFML");
    }

    private void createEclipseTasks() {
        SubprojectTask task = makeTask("eclipseClean", SubprojectTask.class);
        {
            task.setBuildFile(delayedFile(DevConstants.ECLIPSE_CLEAN + "/build.gradle"));
            task.setTasks("eclipse");
            task.dependsOn("extractMcSource", "generateProjects");
        }

        task = makeTask("eclipseFML", SubprojectTask.class);
        {
            task.setBuildFile(delayedFile(DevConstants.ECLIPSE_FML + "/build.gradle"));
            task.setTasks("eclipse");
            task.dependsOn("extractFmlSources", "generateProjects");
        }

        makeTask("eclipse").dependsOn("eclipseClean", "eclipseFML");
    }

    private void createMiscTasks() {
        GeneratePatches task2 = makeTask("genPatches", GeneratePatches.class);
        {
            task2.setPatchDir(delayedFile(DevConstants.FML_PATCH_DIR));
            task2.setOriginalDir(delayedFile(DevConstants.ECLIPSE_CLEAN + "/src/main/java"));
            task2.setChangedDir(delayedFile(DevConstants.ECLIPSE_FML + "/src/minecraft"));
            task2.setOriginalPrefix("../src-base/minecraft");
            task2.setChangedPrefix("../src-work/minecraft");
            task2.setGroup("FML");
        }

        Delete clean = makeTask("cleanFml", Delete.class);
        {
            clean.delete("eclipse");
            clean.setGroup("Clean");
        }

        ObfuscateTask obf = makeTask("obfuscateJar", ObfuscateTask.class);
        {
            obf.setSrg(delayedFile(DevConstants.PACKAGED_SRG));
            obf.setReverse(true);
            obf.setPreFFJar(delayedFile(DevConstants.JAR_SRG_FML));
            obf.setOutJar(delayedFile(DevConstants.REOBF_TMP));
            obf.setBuildFile(delayedFile(DevConstants.ECLIPSE_FML + "/build.gradle"));
            obf.dependsOn("generateProjects", "extractFmlSources", "fixMappings");
        }

        GenBinaryPatches task3 = makeTask("genBinPatches", GenBinaryPatches.class);
        {
            task3.setCleanClient(delayedFile(Constants.JAR_CLIENT_FRESH));
            task3.setCleanServer(delayedFile(Constants.JAR_SERVER_FRESH));
            task3.setCleanMerged(delayedFile(Constants.JAR_MERGED));
            task3.setDirtyJar(delayedFile(DevConstants.REOBF_TMP));
            task3.setDeobfDataLzma(delayedFile(DevConstants.DEOBF_DATA));
            task3.setOutJar(delayedFile(DevConstants.BINPATCH_TMP));
            task3.setSrg(delayedFile(DevConstants.PACKAGED_SRG));
            task3.addPatchList(delayedFileTree(DevConstants.FML_PATCH_DIR));
            task3.dependsOn("obfuscateJar", "compressDeobfData", "fixMappings");
        }

        FMLVersionPropTask prop = makeTask("createVersionProperties", FMLVersionPropTask.class);
        {
            prop.getOutputs().upToDateWhen(Constants.CALL_FALSE);
            prop.setOutputFile(delayedFile(DevConstants.FML_VERSIONF));
        }
    }

    @SuppressWarnings("serial")
    private void createPackageTasks() {
        ChangelogTask log = makeTask("createChangelog", ChangelogTask.class);
        {
            log.getOutputs().upToDateWhen(Constants.CALL_FALSE);
            log.setServerRoot(delayedString("{JENKINS_SERVER}"));
            log.setJobName(delayedString("{JENKINS_JOB}"));
            log.setAuthName(delayedString("{JENKINS_AUTH_NAME}"));
            log.setAuthPassword(delayedString("{JENKINS_AUTH_PASSWORD}"));
            log.setTargetBuild(delayedString("{BUILD_NUM}"));
            log.setOutput(delayedFile(DevConstants.CHANGELOG));
        }

        final DelayedJar uni = makeTask("packageUniversal", DelayedJar.class);
        {
            uni.setClassifier("universal");
            uni.getInputs().file(delayedFile(DevConstants.JSON_REL));
            uni.getOutputs().upToDateWhen(Constants.CALL_FALSE);
            uni.from(delayedZipTree(DevConstants.BINPATCH_TMP));
            uni.from(delayedFileTree(DevConstants.FML_RESOURCES));
            uni.from(delayedFile(DevConstants.FML_VERSIONF));
            uni.from(delayedFile(DevConstants.FML_LICENSE));
            uni.from(delayedFile(DevConstants.FML_CREDITS));
            uni.from(delayedFile(DevConstants.DEOBF_DATA));
            uni.from(delayedFile(DevConstants.CHANGELOG));
            uni.exclude("devbinpatches.pack.lzma");
            uni.setIncludeEmptyDirs(false);
            uni.setManifest(new Closure<Object>(project) {
                public Object call() {
                    Manifest mani = (Manifest) getDelegate();
                    mani.getAttributes().put("Main-Class", delayedString("{MAIN_CLASS}").call());
                    mani.getAttributes().put("Class-Path", getServerClassPath(delayedFile(DevConstants.JSON_REL).call()));
                    return null;
                }
            });
            uni.dependsOn("genBinPatches", "createChangelog", "createVersionProperties");
        }
        project.getArtifacts().add("archives", uni);

        FileFilterTask task = makeTask("generateInstallJson", FileFilterTask.class);
        {
            task.setInputFile(delayedFile(DevConstants.JSON_REL));
            task.setOutputFile(delayedFile(DevConstants.INSTALL_PROFILE));
            task.addReplacement("@minecraft_version@", delayedString("{MC_VERSION}"));
            task.addReplacement("@version@", delayedString("{VERSION}"));
            task.addReplacement("@project@", delayedString("FML"));
            task.addReplacement("@artifact@", delayedString("cpw.mods:fml:{MC_VERSION}-{VERSION}"));
            task.addReplacement("@universal_jar@", new Closure<String>(project) {
                public String call() {
                    return uni.getArchiveName();
                }
            });
            task.addReplacement("@timestamp@", new Closure<String>(project) {
                public String call() {
                    return (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")).format(new Date());
                }
            });
        }

        Zip inst = makeTask("packageInstaller", Zip.class);
        {
            inst.setClassifier("installer");
            inst.from(new Closure<File>(project) {
                public File call() {
                    return uni.getArchivePath();
                }
            });
            inst.from(delayedFile(DevConstants.INSTALL_PROFILE));
            inst.from(delayedFile(DevConstants.CHANGELOG));
            inst.from(delayedFile(DevConstants.FML_LICENSE));
            inst.from(delayedFile(DevConstants.FML_CREDITS));
            inst.from(delayedFile(DevConstants.FML_LOGO));
            inst.from(delayedZipTree(DevConstants.INSTALLER_BASE), new CopyInto("/", "!*.json", "!*.png"));
            inst.dependsOn("packageUniversal", "downloadBaseInstaller", "generateInstallJson");
            inst.setExtension("jar");
        }
        project.getArtifacts().add("archives", inst);

        final Zip patchZip = makeTask("zipPatches", Zip.class);
        {
            patchZip.from(delayedFile(DevConstants.FML_PATCH_DIR));
            patchZip.setArchiveName("fmlpatches.zip");
        }

        final Zip classZip = makeTask("jarClasses", Zip.class);
        {
            classZip.from(delayedZipTree(DevConstants.BINPATCH_TMP), new CopyInto("", "**/*.class"));
            classZip.setArchiveName("binaries.jar");
        }

        final SubprojectTask javadocJar = makeTask("genJavadocs", SubprojectTask.class);
        {
            javadocJar.setBuildFile(delayedFile(DevConstants.ECLIPSE_FML + "/build.gradle"));
            javadocJar.setTasks("jar");
            javadocJar.setConfigureTask(new Action<Task>() {
                @Override
                public void execute(Task obj) {
                    Jar task = (Jar) obj;
                    File file = delayedFile(DevConstants.JAVADOC_TMP).call();
                    task.setDestinationDir(file.getParentFile());
                    task.setArchiveName(file.getName());
                }
            });
        }

        Zip userDev = makeTask("packageUserDev", Zip.class);
        {
            userDev.setClassifier("userdev");
            userDev.from(delayedFile(DevConstants.JSON_DEV));
            userDev.from(delayedFile(DevConstants.JAVADOC_TMP));
            userDev.from(new Closure<File>(project) {
                public File call() {
                    return patchZip.getArchivePath();
                }
            });
            userDev.from(new Closure<File>(project) {
                public File call() {
                    return classZip.getArchivePath();
                }
            });
            userDev.from(delayedFile(DevConstants.CHANGELOG));
            userDev.from(delayedZipTree(DevConstants.BINPATCH_TMP), new CopyInto("", "devbinpatches.pack.lzma"));
            userDev.from(delayedFileTree("{FML_DIR}/src"), new CopyInto("src"));
            userDev.from(delayedFile(DevConstants.DEOBF_DATA), new CopyInto("src/main/resources/"));
            userDev.from(delayedFileTree(DevConstants.MERGE_CFG), new CopyInto("conf"));
            userDev.from(delayedFileTree("{MAPPINGS_DIR}"), new CopyInto("conf", "astyle.cfg"));
            userDev.from(delayedFileTree("{MAPPINGS_DIR}"), new CopyInto("mappings", "*.csv", "!packages.csv"));
            userDev.from(delayedFile(DevConstants.PACKAGED_SRG), new CopyInto("conf"));
            userDev.from(delayedFile(DevConstants.PACKAGED_EXC), new CopyInto("conf"));
            userDev.from(delayedFile(DevConstants.PACKAGED_PATCH), new CopyInto("conf"));
            userDev.rename(".+?\\.json", "dev.json");
            userDev.rename(".+?\\.srg", "packaged.srg");
            userDev.rename(".+?\\.exc", "packaged.exc");
            userDev.rename(".+?\\.patch", "packaged.patch");
            userDev.setIncludeEmptyDirs(false);
            userDev.dependsOn("packageUniversal", "zipPatches", "jarClasses");
            userDev.setExtension("jar");
        }
        project.getArtifacts().add("archives", userDev);

        Zip src = makeTask("packageSrc", Zip.class);
        {
            src.setClassifier("src");
            src.from(delayedFile(DevConstants.CHANGELOG));
            src.from(delayedFile(DevConstants.FML_LICENSE));
            src.from(delayedFile(DevConstants.FML_CREDITS));
            src.from(delayedFile("{FML_DIR}/install"), new CopyInto(null, "!*.gradle"));
            src.from(delayedFile("{FML_DIR}/install"), (new CopyInto(null, "*.gradle")).addExpand("version", delayedString("{MC_VERSION}-{VERSION}")).addExpand("name", "fml"));
            src.from(delayedFile("{FML_DIR}/gradlew"));
            src.from(delayedFile("{FML_DIR}/gradlew.bat"));
            src.from(delayedFile("{FML_DIR}/gradle/wrapper"), new CopyInto("gradle/wrapper"));
            src.rename(".+?\\.gradle", "build.gradle");
            src.dependsOn("createChangelog");
            src.setExtension("zip");
        }
        project.getArtifacts().add("archives", src);
    }

    public static String getVersionFromGit(Project project) {
        return getVersionFromGit(project, project.getProjectDir());
    }

    public static String getVersionFromGit(Project project, File workDir) {
        if (project == null) {
            project = BasePlugin.getProject(null, null);
        }

        String fullVersion = runGit(project, workDir, "describe", "--long");
        fullVersion = fullVersion.replace('-', '.').replaceAll("[^0-9.]", ""); //Normalize splitter, and remove non-numbers
        String[] pts = fullVersion.split("\\.");

        String major = pts[0];
        String minor = pts[1];
        String revision = pts[2];
        String build = "0";

        if (System.getenv().containsKey("BUILD_NUMBER")) {
            build = System.getenv("BUILD_NUMBER");
        }

        String branch = null;
        if (!System.getenv().containsKey("GIT_BRANCH")) {
            branch = runGit(project, workDir, "rev-parse", "--abbrev-ref", "HEAD");
        } else {
            branch = System.getenv("GIT_BRANCH");
            branch = branch.substring(branch.lastIndexOf('/') + 1);
        }

        if (branch != null && (branch.equals("master") || branch.equals("HEAD"))) {
            branch = null;
        }

        StringBuilder out = new StringBuilder();
        out.append(DelayedBase.resolve("{MC_VERSION}", project)).append('-'); // Somehow configure this?
        out.append(major).append('.').append(minor).append('.').append(revision).append('.').append(build);
        if (branch != null) {
            out.append('-').append(branch);
        }

        return out.toString();
    }
}
