package net.minecraftforge.gradle.tasks.dev;

import com.google.common.io.Files;
import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.provider.ClassLoaderProvider;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.dev.FmlDevPlugin;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.AbstractTask;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedList;

public class ObfuscateTask extends DefaultTask {
    private DelayedFile outJar;
    private DelayedFile preFFJar;
    private DelayedFile srg;
    private boolean reverse;
    private DelayedFile buildFile;
    private LinkedList<Action<Project>> configureProject = new LinkedList<Action<Project>>();
    private LinkedList<String> extraSrg = new LinkedList<String>();

    @TaskAction
    public void doTask() throws IOException {
        getLogger().debug("Building child project model...");
        Project childProj = FmlDevPlugin.getProject(getBuildFile(), getProject());
        for (Action<Project> act : configureProject) {
            if (act != null)
                act.execute(childProj);
        }

        AbstractTask compileTask = (AbstractTask) childProj.getTasks().getByName("compileJava");
        AbstractTask jarTask = (AbstractTask) childProj.getTasks().getByName("jar");

        // executing jar task
        getLogger().debug("Executing child Jar task...");
        executeTask(jarTask);

        // copy srg
        File tempSrg = File.createTempFile("obf", ".srg", this.getTemporaryDir());

        // append SRG
        BufferedWriter writer = Files.newWriter(tempSrg, Charset.defaultCharset());
        BufferedReader reader = Files.newReader(getSrg(), Charset.defaultCharset());
        for (String line1 : extraSrg) {
            writer.write(line1);
            writer.newLine();
        }
        String line;
        while ((line = reader.readLine()) != null) {
            writer.write(line);
            writer.newLine();
        }
        reader.close();
        writer.flush();
        writer.close();


        getLogger().debug("Obfuscating jar...");
        obfuscate((File) jarTask.property("archivePath"), tempSrg, (FileCollection) compileTask.property("classpath"));
    }

    private void executeTask(AbstractTask task) {
        for (Object dep : task.getTaskDependencies().getDependencies(task)) {
            executeTask((AbstractTask) dep);
        }

        if (!task.getState().getExecuted()) {
            getLogger().lifecycle(task.getPath());
            task.execute();
        }
    }

    private void obfuscate(File inJar, File srg, FileCollection classpath) throws FileNotFoundException, IOException {
        // load mapping
        JarMapping mapping = new JarMapping();
        mapping.loadMappings(Files.newReader(srg, Charset.defaultCharset()), null, null, reverse);

        // make remapper
        JarRemapper remapper = new JarRemapper(null, mapping);

        // load jar
        Jar input = Jar.init(inJar);

        // ensure that inheritance provider is used
        JointProvider inheritanceProviders = new JointProvider();
        inheritanceProviders.add(new JarProvider(input));

        if (classpath != null)
            inheritanceProviders.add(new ClassLoaderProvider(new URLClassLoader(toUrls(classpath))));

        mapping.setFallbackInheritanceProvider(inheritanceProviders);

        File out = getOutJar();
        if (!out.getParentFile().exists()) //Needed because SS doesn't create it.
        {
            out.getParentFile().mkdirs();
        }

        // remap jar
        remapper.remapJar(input, getOutJar());
    }

    public static URL[] toUrls(FileCollection collection) throws MalformedURLException {
        ArrayList<URL> urls = new ArrayList<URL>();

        for (File file : collection.getFiles())
            urls.add(file.toURI().toURL());

        return urls.toArray(new URL[urls.size()]);
    }

    public File getOutJar() {
        return outJar.call();
    }

    public void setOutJar(DelayedFile outJar) {
        this.outJar = outJar;
    }

    public File getPreFFJar() {
        return preFFJar.call();
    }

    public void setPreFFJar(DelayedFile preFFJar) {
        this.preFFJar = preFFJar;
    }

    public File getSrg() {
        return srg.call();
    }

    public void setSrg(DelayedFile srg) {
        this.srg = srg;
    }

    public boolean isReverse() {
        return reverse;
    }

    public void setReverse(boolean reverse) {
        this.reverse = reverse;
    }

    public void configureProject(Action<Project> action) {
        configureProject.add(action);
    }

    public LinkedList<String> getExtraSrg() {
        return extraSrg;
    }

    public void setExtraSrg(LinkedList<String> extraSrg) {
        this.extraSrg = extraSrg;
    }

    public File getBuildFile() {
        return buildFile.call();
    }

    public void setBuildFile(DelayedFile buildFile) {
        this.buildFile = buildFile;
    }
}
