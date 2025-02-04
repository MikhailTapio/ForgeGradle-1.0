package net.minecraftforge.gradle.tasks;

import au.com.bytecode.opencsv.CSVParser;
import au.com.bytecode.opencsv.CSVReader;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import net.minecraftforge.gradle.StringUtils;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.abstractutil.EditJarTask;
import org.gradle.api.tasks.InputFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RelaceJavadocsTask extends EditJarTask {
    @InputFile
    private DelayedFile methodsCsv;

    @InputFile
    private DelayedFile fieldsCsv;

    private final Map<String, Map<String, String>> methods = new HashMap<String, Map<String, String>>();
    private final Map<String, Map<String, String>> fields = new HashMap<String, Map<String, String>>();

    private static final Pattern METHOD = Pattern.compile("^( {4}|\\t)// JAVADOC METHOD \\$\\$ (func\\_\\d+)$");
    private static final Pattern FIELD = Pattern.compile("^( {4}|\\t)// JAVADOC FIELD \\$\\$ (func\\_\\d+)$");

    @Override
    public void doStuffBefore() throws Throwable {
        CSVReader reader = getReader(getMethodsCsv());
        for (String[] s : reader.readAll()) {
            Map<String, String> temp = new HashMap<String, String>();
            temp.put("name", s[1]);
            temp.put("javadoc", s[3]);
            methods.put(s[0], temp);
        }

        reader = getReader(getFieldsCsv());
        for (String[] s : reader.readAll()) {
            Map<String, String> temp = new HashMap<String, String>();
            temp.put("name", s[1]);
            temp.put("javadoc", s[3]);
            fields.put(s[0], temp);
        }
    }

    public static CSVReader getReader(File file) throws IOException {
        return new CSVReader(Files.newReader(file, Charset.defaultCharset()), CSVParser.DEFAULT_SEPARATOR, CSVParser.DEFAULT_QUOTE_CHARACTER, CSVParser.DEFAULT_ESCAPE_CHARACTER, 1, false);
    }

    @Override
    public String asRead(String text) {
        Matcher matcher;

        String prevLine = null;
        ArrayList<String> newLines = new ArrayList<String>();
        //ImmutableList<String> lines = StringUtils.lines(text);
        for (String line : StringUtils.lines(text)) {
            //String line = lines.get(i);

            // check method
            matcher = METHOD.matcher(line);

            if (matcher.find()) {
                String name = matcher.group(2);

                if (methods.containsKey(name) && methods.get(name).containsKey("name")) {
                    // get javadoc
                    String javadoc = methods.get(name).get("javadoc");

                    if (Strings.isNullOrEmpty(javadoc)) {
                        line = ""; // just delete the marker
                    } else {
                        // replace the marker
                        line = buildJavadoc(matcher.group(1), javadoc, true);

                        if (!Strings.isNullOrEmpty(prevLine) && !prevLine.endsWith("{")) {
                            line = Constants.NEWLINE + line;
                        }
                    }
                }
            }

            // check field
            matcher = FIELD.matcher(line);

            if (matcher.find()) {
                String name = matcher.group(2);

                if (fields.containsKey(name)) {
                    // get javadoc
                    String javadoc = fields.get(name).get("javadoc");

                    if (Strings.isNullOrEmpty(javadoc)) {
                        line = ""; // just delete the marker
                    } else {
                        // replace the marker
                        line = buildJavadoc(matcher.group(1), javadoc, false);

                        if (!Strings.isNullOrEmpty(prevLine) && !prevLine.endsWith("{")) {
                            line = Constants.NEWLINE + line;
                        }
                    }
                }
            }

            prevLine = line;
            newLines.add(line);
        }

        return Joiner.on(Constants.NEWLINE).join(newLines);
    }

    private String buildJavadoc(String indent, String javadoc, boolean isMethod) {
        StringBuilder builder = new StringBuilder();

        if (javadoc.length() >= 70 || isMethod) {
            List<String> list = wrapText(javadoc, 120 - (indent.length() + 3));

            builder.append(indent);
            builder.append("/**");
            builder.append(Constants.NEWLINE);

            for (String line : list) {
                builder.append(indent);
                builder.append(" * ");
                builder.append(line);
                builder.append(Constants.NEWLINE);
            }

            builder.append(indent);
            builder.append(" */");
            builder.append(Constants.NEWLINE);

        }
        // one line
        else {
            builder.append(indent);
            builder.append("/** ");
            builder.append(javadoc);
            builder.append(" */");
            builder.append(Constants.NEWLINE);
        }

        return builder.toString().replace(indent, indent);
    }

    private static List<String> wrapText(String text, int len) {
        // return empty array for null text
        if (text == null) {
            return new ArrayList<String>();
        }

        // return text if len is zero or less
        if (len <= 0) {
            return new ArrayList<String>(Arrays.asList(text));
        }

        // return text if less than length
        if (text.length() <= len) {
            return new ArrayList<String>(Arrays.asList(text));
        }

        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        StringBuilder word = new StringBuilder();
        int tempNum;

        // each char in array
        for (char c : text.toCharArray()) {
            // its a wordBreaking character.
            if (c == ' ' || c == ',' || c == '-') {
                // add the character to the word
                word.append(c);

                // its a space. set TempNum to 1, otherwise leave it as a wrappable char
                tempNum = Character.isWhitespace(c) ? 1 : 0;

                // subtract tempNum from the length of the word
                if ((line.length() + word.length() - tempNum) > len) {
                    lines.add(line.toString());
                    line.delete(0, line.length());
                }

                // new word, add it to the next line and clear the word
                line.append(word);
                word.delete(0, word.length());

            }
            // not a linebreak char
            else {
                // add it to the word and move on
                word.append(c);
            }
        }

        // handle any extra chars in current word
        if (word.length() > 0) {
            if ((line.length() + word.length()) > len) {
                lines.add(line.toString());
                line.delete(0, line.length());
            }
            line.append(word);
        }

        // handle extra line
        if (line.length() > 0) {
            lines.add(line.toString());
        }

        List<String> temp = new ArrayList<String>(lines.size());
        for (String s : lines) {
            temp.add(s.trim());
        }
        return temp;
    }

    @Override
    public void doStuffMiddle() throws Throwable {
    }

    @Override
    public void doStuffAfter() throws Throwable {
    }

    public File getMethodsCsv() {
        return methodsCsv.call();
    }

    public void setMethodsCsv(DelayedFile methodsCsv) {
        this.methodsCsv = methodsCsv;
    }

    public File getFieldsCsv() {
        return fieldsCsv.call();
    }

    public void setFieldsCsv(DelayedFile fieldsCsv) {
        this.fieldsCsv = fieldsCsv;
    }
}
