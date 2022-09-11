/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 *
 * The Original Software is NetBeans. The Initial Developer of the Original
 * Software is Sun Microsystems, Inc. Portions Copyright 1997-2007 Sun
 * Microsystems, Inc. All Rights Reserved.
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 */
package net.minecraftforge.gradle.patching;

import com.cloudbees.diff.Hunk;
import com.cloudbees.diff.PatchException;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOLEN FROM diff4j v1.1
 * <p>
 * Applies contextual patches to files. The patch file can contain patches for multiple files.
 *
 * @author Maros Sandor
 */
public final class ContextualPatch {

    public static final String MAGIC = "# This patch file was generated by NetBeans IDE"; // NOI18N    

    // first seen in mercurial diffs: characters after the second @@ - ignore them  
    private final Pattern unifiedRangePattern = Pattern.compile("@@ -(\\d+)(,\\d+)? \\+(\\d+)(,\\d+)? @@(\\s.*)?");
    private final Pattern baseRangePattern = Pattern.compile("\\*\\*\\* (\\d+)(,\\d+)? \\*\\*\\*\\*");
    private final Pattern modifiedRangePattern = Pattern.compile("--- (\\d+)(,\\d+)? ----");
    private final Pattern normalChangeRangePattern = Pattern.compile("(\\d+),(\\d+)c(\\d+),(\\d+)");
    private final Pattern normalAddRangePattern = Pattern.compile("(\\d+)a(\\d+),(\\d+)");
    private final Pattern normalDeleteRangePattern = Pattern.compile("(\\d+),(\\d+)d(\\d+)");
    private final Pattern binaryHeaderPattern = Pattern.compile("MIME: (.*?); encoding: (.*?); length: (-?\\d+?)");

    private final File patchFile;
    private final File suggestedContext;

    private String patchString;
    private IContextProvider contextProvider;
    private int maxFuzz = 0;
    private boolean c14nWhitespace = false;
    private boolean c14nAccess = false;


    private File context;
    private BufferedReader patchReader;
    private String patchLine;
    private boolean patchLineRead;
    private int lastPatchedLine;    // the last line that was successfuly patched

    public static ContextualPatch create(File patchFile, File context) {
        return new ContextualPatch(patchFile, context);
    }

    public static ContextualPatch create(String patchString, IContextProvider context) {
        return new ContextualPatch(patchString, context);
    }

    private ContextualPatch(String patchString, IContextProvider context) {
        this.patchString = patchString;
        this.contextProvider = context;
        patchFile = null;
        suggestedContext = null;
    }

    private ContextualPatch(File patchFile, File context) {
        this.patchFile = patchFile;
        this.suggestedContext = context;
    }

    public ContextualPatch setMaxFuzz(int maxFuzz) {
        this.maxFuzz = maxFuzz;
        return this;
    }

    public ContextualPatch setWhitespaceC14N(boolean canonicalize) {
        this.c14nWhitespace = canonicalize;
        return this;
    }

    public ContextualPatch setAccessC14N(boolean canonicalize) {
        this.c14nAccess = canonicalize;
        return this;
    }

    /**
     * @param dryRun true if the method should not make any modifications to files, false otherwise
     * @return
     * @throws PatchException
     * @throws IOException
     */
    public List<PatchReport> patch(boolean dryRun) throws PatchException, IOException {
        List<PatchReport> report = new ArrayList<PatchReport>();
        init();
        try {
            patchLine = patchReader.readLine();
            List<SinglePatch> patches = new ArrayList<SinglePatch>();
            for (; ; ) {
                SinglePatch patch = getNextPatch();
                if (patch == null) {
                    break;
                }
                patches.add(patch);
            }
            computeContext(patches);
            for (SinglePatch patch : patches) {
                try {
                    report.add(applyPatch(patch, dryRun));
                    //report.add(new PatchReport(patch.targetFile, computeBackup(patch.targetFile), patch.binary, PatchStatus.Patched, null));
                } catch (Exception e) {
                    report.add(new PatchReport(patch.targetPath, patch.binary, PatchStatus.Failure, e, new ArrayList<HunkReport>()));
                }
            }
            return report;
        } finally {
            if (patchReader != null) {
                try {
                    patchReader.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void init() throws IOException {
        if (patchString != null) {
            //Just read the string as is, without trying to read the magic/encoding as the string shuldn't need encoding!
            patchReader = new BufferedReader(new StringReader(patchString));
            return;
        }

        patchReader = new BufferedReader(new FileReader(patchFile));
        String encoding = "ISO-8859-1";
        String line = patchReader.readLine();
        if (MAGIC.equals(line)) {
            encoding = "utf8"; // NOI18N
            line = patchReader.readLine();
        }
        patchReader.close();

        byte[] buffer = new byte[MAGIC.length()];
        InputStream in = new FileInputStream(patchFile);
        int read = in.read(buffer);
        in.close();
        if (read != -1 && MAGIC.equals(new String(buffer, "utf8"))) {  // NOI18N
            encoding = "utf8"; // NOI18N
        }
        patchReader = new BufferedReader(new InputStreamReader(new FileInputStream(patchFile), encoding));
    }

    private PatchReport applyPatch(SinglePatch patch, boolean dryRun) throws IOException, PatchException {
        lastPatchedLine = 1;
        List<HunkReport> ret = new ArrayList<HunkReport>();

        if (this.contextProvider != null) {
            List<String> target = contextProvider.getData(patch.targetPath);

            if (target != null && !patch.binary) {
                if (patchCreatesNewFileThatAlreadyExists(patch, target)) { //Check if the patch doesn't need to be applied...
                    for (int x = 0; x < patch.hunks.length; x++) {
                        ret.add(new HunkReport(PatchStatus.Skipped, null, 0, 0, x));
                    }
                    return new PatchReport(patch.targetPath, patch.binary, PatchStatus.Skipped, null, ret);
                }
            } else if (target == null) {
                target = new ArrayList<String>();
            }

            if (patch.mode == Mode.DELETE) {
                target = new ArrayList<String>();
            } else {
                if (!patch.binary) {
                    int x = 0;
                    for (Hunk hunk : patch.hunks) {
                        x++;
                        try {
                            ret.add(applyHunk(target, hunk, x));
                        } catch (Exception e) {
                            ret.add(new HunkReport(PatchStatus.Failure, e, 0, 0, x));
                        }
                    }
                }
            }

            if (!dryRun) {
                contextProvider.setData(patch.targetPath, target);
            }
        } else {
            List<String> target;
            patch.targetFile = computeTargetFile(patch);
            if (patch.targetFile.exists() && !patch.binary) {
                target = readFile(patch.targetFile);
                if (patchCreatesNewFileThatAlreadyExists(patch, target)) { //Check if the patch doesn't need to be applied...
                    for (int x = 0; x < patch.hunks.length; x++) {
                        ret.add(new HunkReport(PatchStatus.Skipped, null, 0, 0, x));
                    }
                    return new PatchReport(patch.targetPath, patch.binary, PatchStatus.Skipped, null, ret);
                }
            } else {
                target = new ArrayList<String>();
            }
            if (patch.mode == Mode.DELETE) {
                target = new ArrayList<String>();
            } else {
                if (!patch.binary) {
                    int x = 0;
                    for (Hunk hunk : patch.hunks) {
                        x++;
                        try {
                            ret.add(applyHunk(target, hunk, x));
                        } catch (Exception e) {
                            ret.add(new HunkReport(PatchStatus.Failure, e, 0, 0, x));
                        }

                    }
                }
            }
            if (!dryRun) {
                backup(patch.targetFile);
                writeFile(patch, target);
            }
        }

        for (HunkReport hunk : ret) {
            if (hunk.getStatus() == PatchStatus.Failure) {
                return new PatchReport(patch.targetPath, patch.binary, PatchStatus.Failure, hunk.getFailure(), ret);
            }
        }
        return new PatchReport(patch.targetPath, patch.binary, PatchStatus.Patched, null, ret);
    }

    private boolean patchCreatesNewFileThatAlreadyExists(SinglePatch patch, List<String> originalFile) throws PatchException {
        if (patch.hunks.length != 1) {
            return false;
        }
        Hunk hunk = patch.hunks[0];
        if (hunk.baseStart != 0 || hunk.baseCount != 0 || hunk.modifiedStart != 1 || hunk.modifiedCount != originalFile.size()) {
            return false;
        }

        List<String> target = new ArrayList<String>(hunk.modifiedCount);
        applyHunk(target, hunk, 0);
        return target.equals(originalFile);
    }

    private void backup(File target) throws IOException {
        if (target.exists()) {
            copyStreamsCloseAll(new FileOutputStream(computeBackup(target)), new FileInputStream(target));
        }
    }

    private File computeBackup(File target) {
        return new File(target.getParentFile(), target.getName() + ".original~");
    }

    private void copyStreamsCloseAll(OutputStream writer, InputStream reader) throws IOException {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = reader.read(buffer)) != -1) {
            writer.write(buffer, 0, n);
        }
        writer.close();
        reader.close();
    }

    private void writeFile(SinglePatch patch, List<String> lines) throws IOException {
        if (patch.mode == Mode.DELETE) {
            patch.targetFile.delete();
            return;
        }

        patch.targetFile.getParentFile().mkdirs();
        if (patch.binary) {
            if (patch.hunks.length == 0) {
                patch.targetFile.delete();
            } else {
                byte[] content = Base64.decode(patch.hunks[0].lines);
                copyStreamsCloseAll(new FileOutputStream(patch.targetFile), new ByteArrayInputStream(content));
            }
        } else {
            PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(patch.targetFile), getEncoding(patch.targetFile)));
            try {
                if (lines.size() == 0) {
                    return;
                }
                for (String line : lines.subList(0, lines.size() - 1)) {
                    w.println(line);
                }
                w.print(lines.get(lines.size() - 1));
                if (!patch.noEndingNewline) {
                    w.println();
                }
            } finally {
                w.close();
            }
        }
    }

    private HunkReport applyHunk(List<String> target, Hunk hunk, int hunkID) throws PatchException {
        int idx = -1;
        int fuzz = 0;
        for (; idx == -1 && fuzz <= this.maxFuzz; fuzz++) {
            idx = findHunkIndex(target, hunk, fuzz, hunkID);
            if (idx != -1) {
                break;
            }
        }
        if (idx == -1) {
            throw new PatchException("Cannot find hunk target");
        }
        return applyHunk(target, hunk, idx, false, fuzz, hunkID);
    }

    private int findHunkIndex(List<String> target, Hunk hunk, int fuzz, int hunkID) throws PatchException {
        int idx = hunk.modifiedStart;  // first guess from the hunk range specification
        if (idx >= lastPatchedLine && applyHunk(target, hunk, idx, true, fuzz, hunkID).getStatus().isSuccess()) {
            return idx;
        } else {
            // try to search for the context
            for (int i = idx - 1; i >= lastPatchedLine; i--) {
                if (applyHunk(target, hunk, i, true, fuzz, hunkID).getStatus().isSuccess()) {
                    return i;
                }
            }
            for (int i = idx + 1; i < target.size(); i++) {
                if (applyHunk(target, hunk, i, true, fuzz, hunkID).getStatus().isSuccess()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * @return true if the application succeeded
     */
    private HunkReport applyHunk(List<String> target, Hunk hunk, int idx, boolean dryRun, int fuzz, int hunkID) throws PatchException {
        int startIdx = idx;
        idx--; // indices in the target list are 0-based
        int hunkIdx = -1;
        for (String hunkLine : hunk.lines) {
            hunkIdx++;
            boolean isAddition = isAdditionLine(hunkLine);
            if (!isAddition) {
                if (idx >= target.size()) {
                    if (dryRun) {
                        return new HunkReport(PatchStatus.Failure, null, idx, fuzz, hunkID);
                    } else {
                        throw new PatchException("Unapplicable hunk #" + hunkID + " @@ " + startIdx);
                    }
                }
                boolean match = similar(target.get(idx), hunkLine.substring(1), hunkLine.charAt(0));
                if (!match && fuzz != 0 && !isRemovalLine(hunkLine)) {
                    match = (hunkIdx < fuzz || hunkIdx >= hunk.lines.size() - fuzz ? true : match);
                }
                if (!match) {
                    if (dryRun) {
                        return new HunkReport(PatchStatus.Failure, null, idx, fuzz, hunkID);
                    } else {
                        throw new PatchException("Unapplicable hunk #" + hunkID + " @@ " + startIdx);
                    }
                }
            }
            if (dryRun) {
                if (isAddition) {
                    idx--;
                }
            } else {
                if (isAddition) {
                    target.add(idx, hunkLine.substring(1));
                } else if (isRemovalLine(hunkLine)) {
                    target.remove(idx);
                    idx--;
                }
            }
            idx++;
        }
        idx++; // indices in the target list are 0-based
        lastPatchedLine = idx;
        return new HunkReport((fuzz != 0 ? PatchStatus.Fuzzed : PatchStatus.Patched), null, startIdx, fuzz, hunkID);
    }

    private boolean isAdditionLine(String hunkLine) {
        return hunkLine.charAt(0) == '+';
    }

    private boolean isRemovalLine(String hunkLine) {
        return hunkLine.charAt(0) == '-';
    }

    private Charset getEncoding(File file) {
        return Charset.defaultCharset();
    }

    private List<String> readFile(File target) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(target), getEncoding(target)));
        try {
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } finally {
            IOUtils.closeQuietly(r);
        }
    }

    private SinglePatch getNextPatch() throws IOException, PatchException {
        SinglePatch patch = new SinglePatch();
        for (; ; ) {
            String line = readPatchLine();
            if (line == null) {
                return null;
            }

            if (line.startsWith("Index:")) {
                patch.targetPath = line.substring(6).trim();
            } else if (line.startsWith("MIME: application/octet-stream;")) {
                unreadPatchLine();
                readBinaryPatchContent(patch);
                break;
            } else if (line.startsWith("--- ")) {
                unreadPatchLine();
                readPatchContent(patch);
                break;
            } else if (line.startsWith("*** ")) {
                unreadPatchLine();
                readContextPatchContent(patch);
                break;
            } else if (isNormalDiffRange(line)) {
                unreadPatchLine();
                readNormalPatchContent(patch);
                break;
            }
        }
        return patch;
    }

    private boolean isNormalDiffRange(String line) {
        return normalAddRangePattern.matcher(line).matches()
                || normalChangeRangePattern.matcher(line).matches()
                || normalDeleteRangePattern.matcher(line).matches();
    }

    /**
     * Reads binary diff hunk.
     */
    private void readBinaryPatchContent(SinglePatch patch) throws PatchException, IOException {
        List<Hunk> hunks = new ArrayList<Hunk>();
        Hunk hunk = new Hunk();
        for (; ; ) {
            String line = readPatchLine();
            if (line == null || line.startsWith("Index:") || line.length() == 0) {
                unreadPatchLine();
                break;
            }
            if (patch.binary) {
                hunk.lines.add(line);
            } else {
                Matcher m = binaryHeaderPattern.matcher(line);
                if (m.matches()) {
                    patch.binary = true;
                    int length = Integer.parseInt(m.group(3));
                    if (length == -1) {
                        break;
                    }
                    hunks.add(hunk);
                }
            }
        }
        patch.hunks = hunks.toArray(new Hunk[hunks.size()]);
    }

    /**
     * Reads normal diff hunks.
     */
    private void readNormalPatchContent(SinglePatch patch) throws IOException, PatchException {
        List<Hunk> hunks = new ArrayList<Hunk>();
        Hunk hunk = null;
        Matcher m;
        for (; ; ) {
            String line = readPatchLine();
            if (line == null || line.startsWith("Index:")) {
                unreadPatchLine();
                break;
            }
            if ((m = normalAddRangePattern.matcher(line)).matches()) {
                hunk = new Hunk();
                hunks.add(hunk);
                parseNormalRange(hunk, m);
            } else if ((m = normalChangeRangePattern.matcher(line)).matches()) {
                hunk = new Hunk();
                hunks.add(hunk);
                parseNormalRange(hunk, m);
            } else if ((m = normalDeleteRangePattern.matcher(line)).matches()) {
                hunk = new Hunk();
                hunks.add(hunk);
                parseNormalRange(hunk, m);
            } else {
                if (line.startsWith("> ")) {
                    hunk.lines.add("+" + line.substring(2));
                } else if (line.startsWith("< ")) {
                    hunk.lines.add("-" + line.substring(2));
                } else if (line.startsWith("---")) {
                    // ignore
                } else {
                    throw new PatchException("Invalid hunk line: " + line);
                }
            }
        }
        patch.hunks = hunks.toArray(new Hunk[hunks.size()]);
    }

    private void parseNormalRange(Hunk hunk, Matcher m) {
        if (m.pattern() == normalAddRangePattern) {
            hunk.baseStart = Integer.parseInt(m.group(1));
            hunk.baseCount = 0;
            hunk.modifiedStart = Integer.parseInt(m.group(2));
            hunk.modifiedCount = Integer.parseInt(m.group(3)) - hunk.modifiedStart + 1;
        } else if (m.pattern() == normalDeleteRangePattern) {
            hunk.baseStart = Integer.parseInt(m.group(1));
            hunk.baseCount = Integer.parseInt(m.group(2)) - hunk.baseStart + 1;
            hunk.modifiedStart = Integer.parseInt(m.group(3));
            hunk.modifiedCount = 0;
        } else {
            hunk.baseStart = Integer.parseInt(m.group(1));
            hunk.baseCount = Integer.parseInt(m.group(2)) - hunk.baseStart + 1;
            hunk.modifiedStart = Integer.parseInt(m.group(3));
            hunk.modifiedCount = Integer.parseInt(m.group(4)) - hunk.modifiedStart + 1;
        }
    }

    /**
     * Reads context diff hunks.
     */
    private void readContextPatchContent(SinglePatch patch) throws IOException, PatchException {
        String base = readPatchLine();
        if (base == null || !base.startsWith("*** ")) {
            throw new PatchException("Invalid context diff header: " + base);
        }
        String modified = readPatchLine();
        if (modified == null || !modified.startsWith("--- ")) {
            throw new PatchException("Invalid context diff header: " + modified);
        }
        if (patch.targetPath == null) {
            computeTargetPath(base, modified, patch);
        }

        List<Hunk> hunks = new ArrayList<Hunk>();
        Hunk hunk = null;

        int lineCount = -1;
        for (; ; ) {
            String line = readPatchLine();
            if (line == null || line.length() == 0 || line.startsWith("Index:")) {
                unreadPatchLine();
                break;
            } else if (line.startsWith("***************")) {
                hunk = new Hunk();
                parseContextRange(hunk, readPatchLine());
                hunks.add(hunk);
            } else if (line.startsWith("--- ")) {
                lineCount = 0;
                parseContextRange(hunk, line);
                hunk.lines.add(line);
            } else {
                char c = line.charAt(0);
                if (c == ' ' || c == '+' || c == '-' || c == '!') {
                    if (lineCount < hunk.modifiedCount) {
                        hunk.lines.add(line);
                        if (lineCount != -1) {
                            lineCount++;
                        }
                    }
                } else {
                    throw new PatchException("Invalid hunk line: " + line);
                }
            }
        }
        patch.hunks = hunks.toArray(new Hunk[hunks.size()]);
        convertContextToUnified(patch);
    }

    private void convertContextToUnified(SinglePatch patch) throws PatchException {
        Hunk[] unifiedHunks = new Hunk[patch.hunks.length];
        int idx = 0;
        for (Hunk hunk : patch.hunks) {
            unifiedHunks[idx++] = convertContextToUnified(hunk);
        }
        patch.hunks = unifiedHunks;
    }

    private Hunk convertContextToUnified(Hunk hunk) throws PatchException {
        Hunk unifiedHunk = new Hunk();
        unifiedHunk.baseStart = hunk.baseStart;
        unifiedHunk.modifiedStart = hunk.modifiedStart;
        int split = -1;
        for (int i = 0; i < hunk.lines.size(); i++) {
            if (hunk.lines.get(i).startsWith("--- ")) {
                split = i;
                break;
            }
        }
        if (split == -1) {
            throw new PatchException("Missing split divider in context patch");
        }

        int baseIdx = 0;
        int modifiedIdx = split + 1;
        List<String> unifiedLines = new ArrayList<String>(hunk.lines.size());
        for (; baseIdx < split || modifiedIdx < hunk.lines.size(); ) {
            String baseLine = baseIdx < split ? hunk.lines.get(baseIdx) : "~";
            String modifiedLine = modifiedIdx < hunk.lines.size() ? hunk.lines.get(modifiedIdx) : "~";
            if (baseLine.startsWith("- ")) {
                unifiedLines.add("-" + baseLine.substring(2));
                unifiedHunk.baseCount++;
                baseIdx++;
            } else if (modifiedLine.startsWith("+ ")) {
                unifiedLines.add("+" + modifiedLine.substring(2));
                unifiedHunk.modifiedCount++;
                modifiedIdx++;
            } else if (baseLine.startsWith("! ")) {
                unifiedLines.add("-" + baseLine.substring(2));
                unifiedHunk.baseCount++;
                baseIdx++;
            } else if (modifiedLine.startsWith("! ")) {
                unifiedLines.add("+" + modifiedLine.substring(2));
                unifiedHunk.modifiedCount++;
                modifiedIdx++;
            } else if (baseLine.startsWith("  ") && modifiedLine.startsWith("  ")) {
                unifiedLines.add(baseLine.substring(1));
                unifiedHunk.baseCount++;
                unifiedHunk.modifiedCount++;
                baseIdx++;
                modifiedIdx++;
            } else if (baseLine.startsWith("  ")) {
                unifiedLines.add(baseLine.substring(1));
                unifiedHunk.baseCount++;
                unifiedHunk.modifiedCount++;
                baseIdx++;
            } else if (modifiedLine.startsWith("  ")) {
                unifiedLines.add(modifiedLine.substring(1));
                unifiedHunk.baseCount++;
                unifiedHunk.modifiedCount++;
                modifiedIdx++;
            } else {
                throw new PatchException("Invalid context patch: " + baseLine);
            }
        }
        unifiedHunk.lines = unifiedLines;
        return unifiedHunk;
    }

    /**
     * Reads unified diff hunks.
     */
    private void readPatchContent(SinglePatch patch) throws IOException, PatchException {
        String base = readPatchLine();
        if (base == null || !base.startsWith("--- ")) {
            throw new PatchException("Invalid unified diff header: " + base);
        }
        String modified = readPatchLine();
        if (modified == null || !modified.startsWith("+++ ")) {
            throw new PatchException("Invalid unified diff header: " + modified);
        }
        if (patch.targetPath == null) {
            computeTargetPath(base, modified, patch);
        }

        List<Hunk> hunks = new ArrayList<Hunk>();
        Hunk hunk = null;

        for (; ; ) {
            String line = readPatchLine();
            if (line == null || line.length() == 0 || line.startsWith("Index:")) {
                unreadPatchLine();
                break;
            }
            char c = line.charAt(0);
            if (c == '@') {
                hunk = new Hunk();
                parseRange(hunk, line);
                hunks.add(hunk);
            } else if (c == ' ' || c == '+' || c == '-') {
                hunk.lines.add(line);
            } else if (line.equals(Hunk.ENDING_NEWLINE)) {
                patch.noEndingNewline = true;
            } else {
                // first seen in mercurial diffs: be optimistic, this is probably the end of this patch  
                unreadPatchLine();
                break;
            }
        }
        patch.hunks = hunks.toArray(new Hunk[hunks.size()]);
    }

    private void computeTargetPath(String base, String modified, SinglePatch patch) {
        base = base.substring("+++ ".length());
        modified = modified.substring("--- ".length());
        // first seen in mercurial diffs: base and modified paths are different: base starts with "a/" and modified starts with "b/"
        if ((base.equals("/dev/null") || base.startsWith("a/")) && (modified.equals("/dev/null") || modified.startsWith("b/"))) {
            if (base.startsWith("a/")) {
                base = base.substring(2);
            }
            if (modified.startsWith("b/")) {
                modified = modified.substring(2);
            }
        }
        base = untilTab(base).trim();
        if (base.equals("/dev/null")) {
            // "/dev/null" in base indicates a new file
            patch.targetPath = untilTab(modified).trim();
            patch.mode = Mode.ADD;
        } else {
            patch.targetPath = base;
            patch.mode = modified.equals("/dev/null") ? Mode.DELETE : Mode.CHANGE;
        }
    }

    private String untilTab(String base) {
        int pathEndIdx = base.indexOf('\t');
        if (pathEndIdx > 0) {
            base = base.substring(0, pathEndIdx);
        }
        return base;
    }

    private void parseRange(Hunk hunk, String range) throws PatchException {
        Matcher m = unifiedRangePattern.matcher(range);
        if (!m.matches()) {
            throw new PatchException("Invalid unified diff range: " + range);
        }
        hunk.baseStart = Integer.parseInt(m.group(1));
        hunk.baseCount = m.group(2) != null ? Integer.parseInt(m.group(2).substring(1)) : 1;
        hunk.modifiedStart = Integer.parseInt(m.group(3));
        hunk.modifiedCount = m.group(4) != null ? Integer.parseInt(m.group(4).substring(1)) : 1;
    }

    private void parseContextRange(Hunk hunk, String range) throws PatchException {
        if (range.charAt(0) == '*') {
            Matcher m = baseRangePattern.matcher(range);
            if (!m.matches()) {
                throw new PatchException("Invalid context diff range: " + range);
            }
            hunk.baseStart = Integer.parseInt(m.group(1));
            hunk.baseCount = m.group(2) != null ? Integer.parseInt(m.group(2).substring(1)) : 1;
            hunk.baseCount -= hunk.baseStart - 1;
        } else {
            Matcher m = modifiedRangePattern.matcher(range);
            if (!m.matches()) {
                throw new PatchException("Invalid context diff range: " + range);
            }
            hunk.modifiedStart = Integer.parseInt(m.group(1));
            hunk.modifiedCount = m.group(2) != null ? Integer.parseInt(m.group(2).substring(1)) : 1;
            hunk.modifiedCount -= hunk.modifiedStart - 1;
        }
    }

    private String readPatchLine() throws IOException {
        if (patchLineRead) {
            patchLine = patchReader.readLine();
        } else {
            patchLineRead = true;
        }
        return patchLine;
    }

    private void unreadPatchLine() {
        patchLineRead = false;
    }

    private void computeContext(List<SinglePatch> patches) {
        File bestContext = suggestedContext;
        int bestContextMatched = 0;
        for (context = suggestedContext; context != null; context = context.getParentFile()) {
            int patchedFiles = 0;
            for (SinglePatch patch : patches) {
                try {
                    applyPatch(patch, true);
                    patchedFiles++;
                } catch (Exception e) {
                    // patch failed to apply
                }
            }
            if (patchedFiles > bestContextMatched) {
                bestContextMatched = patchedFiles;
                bestContext = context;
                if (patchedFiles == patches.size()) {
                    break;
                }
            }
        }
        context = bestContext;
    }

    private File computeTargetFile(SinglePatch patch) {
        if (patch.targetPath == null) {
            patch.targetPath = context.getAbsolutePath();
        }
        if (context.isFile()) {
            return context;
        }
        return new File(context, patch.targetPath);
    }

    private static class SinglePatch {
        //String targetIndex;
        String targetPath;
        Hunk[] hunks;
        //boolean targetMustExist = true;     // == false if the patch contains one hunk with just additions ('+' lines)
        File targetFile;                 // computed later
        boolean noEndingNewline;            // resulting file should not end with a newline
        boolean binary;                  // binary patches contain one encoded Hunk
        Mode mode;
    }

    enum Mode {
        /**
         * Update to existing file
         */
        CHANGE,
        /**
         * Adding a new file
         */
        ADD,
        /**
         * Deleting an existing file
         */
        DELETE
    }

    public static enum PatchStatus {
        Patched(true),
        Missing(false),
        Failure(false),
        Skipped(true),
        Fuzzed(true);

        private boolean success;

        PatchStatus(boolean success) {
            this.success = success;
        }

        public boolean isSuccess() {
            return success;
        }
    }

    public static final class PatchReport {

        private String target;
        private boolean binary;
        private PatchStatus status;
        private Throwable failure;
        private List<HunkReport> hunks;

        PatchReport(String target, boolean binary, PatchStatus status, Throwable failure, List<HunkReport> hunks) {
            this.target = target;
            this.binary = binary;
            this.status = status;
            this.failure = failure;
            this.hunks = hunks;
        }

        public String getTarget() {
            return target;
        }

        public boolean isBinary() {
            return binary;
        }

        public PatchStatus getStatus() {
            return status;
        }

        public Throwable getFailure() {
            return failure;
        }

        public List<HunkReport> getHunks() {
            return hunks;
        }
    }

    public static interface IContextProvider {
        public List<String> getData(String target);

        public void setData(String target, List<String> data);
    }

    public static class HunkReport {
        private PatchStatus status;
        private Throwable failure;
        private int index;
        private int fuzz;
        private int hunkID;

        public HunkReport(PatchStatus status, Throwable failure, int index, int fuzz, int hunkID) {
            this.status = status;
            this.failure = failure;
            this.index = index;
            this.fuzz = fuzz;
            this.hunkID = hunkID;
        }

        public PatchStatus getStatus() {
            return status;
        }

        public Throwable getFailure() {
            return failure;
        }

        public int getIndex() {
            return index;
        }

        public int getFuzz() {
            return fuzz;
        }

        public int getHunkID() {
            return hunkID;
        }
    }

    private boolean similar(String target, String hunk, char lineType) {
        if (c14nAccess) {
            if (c14nWhitespace) {
                target = target.replaceAll("[\t| ]+", " ");
                hunk = hunk.replaceAll("[\t| ]+", " ");
            }
            String[] t = target.split(" ");
            String[] h = hunk.split(" ");
            if (t.length != h.length) {
                return false;
            }
            for (int x = 0; x < t.length; x++) {
                if (isAccess(t[x]) && isAccess(h[x])) {
                    continue;
                } else {
                    if (!t[x].equals(h[x])) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (c14nWhitespace) {
            return target.replaceAll("[\t| ]+", " ").equals(hunk.replaceAll("[\t| ]+", " "));
        } else {
            return target.equals(hunk);
        }
    }

    private boolean isAccess(String data) {
        return data.equalsIgnoreCase("public") ||
                data.equalsIgnoreCase("private") ||
                data.equalsIgnoreCase("protected");
    }
}