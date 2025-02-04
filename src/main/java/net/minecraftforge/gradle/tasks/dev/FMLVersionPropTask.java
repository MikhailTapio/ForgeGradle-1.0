package net.minecraftforge.gradle.tasks.dev;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import groovy.lang.Closure;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedString;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;

public class FMLVersionPropTask extends DefaultTask {
    @OutputFile
    DelayedFile outputFile;

    private Closure<String> version;

    @TaskAction
    public void doTask() throws IOException {
        String[] v;
        if (this.version == null)
            v = ((String) getProject().getVersion()).split("-")[1].split("\\.");
        else
            v = this.version.call().split("-")[1].split("\\.");
        String data =
                "fmlbuild.major.number=" + v[0] + "\n" +
                        "fmlbuild.minor.number=" + v[1] + "\n" +
                        "fmlbuild.revision.number=" + v[2] + "\n" +
                        "fmlbuild.build.number=" + v[3] + "\n" +
                        "fmlbuild.mcversion=" + new DelayedString(getProject(), "{MC_VERSION}").call() + "\n" +
                        "fmlbuild.mcpversion=" + new DelayedString(getProject(), "{MCP_VERSION}").call() + "\n";
        //fmlbuild.deobfuscation.hash -- Not actually used anywhere
        Files.write(data.getBytes(Charsets.UTF_8), getOutputFile());
    }

    public void setOutputFile(DelayedFile output) {
        this.outputFile = output;
    }

    public File getOutputFile() {
        return outputFile.call();
    }

    public void setVersion(Closure<String> value) {
        this.version = value;
    }
}
