package com.example.irisqualitycapture;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses captured iris image files from the Download directory and groups them
 * into a hierarchy: SubjectID → list of CaptureGroups (sorted by session+trial).
 *
 * Filename convention (produced by MainActivity3):
 *   {subjectID}_{sessionID}_{trialNum}_{eye}.png          ← base (variant 0)
 *   {subjectID}_{sessionID}_{trialNum}_{eye}_{n}.png      ← variant n
 *
 * Where {eye} is exactly the word "left" or "right".
 * SubjectID / sessionID / trialNum are always numeric (enforced by the UI).
 */
public class ImageFileParser {

    /**
     * Scans {@code dir} for PNG files and returns a map of subjectID →
     * sorted list of CaptureGroups.  The outer map is a TreeMap so subject IDs
     * are in natural (alphabetical/numeric) order.
     */
    public static TreeMap<String, List<CaptureGroup>> parse(File dir) {
        // key1 = subjectID, key2 = sessionID + "_" + trialNum
        Map<String, Map<String, CaptureGroup>> accumulator = new LinkedHashMap<>();

        if (dir == null || !dir.isDirectory()) return new TreeMap<>();

        File[] files = dir.listFiles(f ->
                f.isFile() && f.getName().endsWith(".png")
                        && !f.getName().startsWith("temp_"));

        if (files == null) return new TreeMap<>();

        for (File file : files) {
            ParsedFilename pf = parseFilename(file.getName());
            if (pf == null) continue;

            Map<String, CaptureGroup> subjectMap =
                    accumulator.computeIfAbsent(pf.subjectID, k -> new LinkedHashMap<>());

            String groupKey = pf.sessionID + "_" + pf.trialNum;
            CaptureGroup group = subjectMap.computeIfAbsent(groupKey,
                    k -> new CaptureGroup(pf.subjectID, pf.sessionID, pf.trialNum));

            if ("left".equals(pf.eye)) {
                group.leftEyeFiles.add(file);
            } else {
                group.rightEyeFiles.add(file);
            }
        }

        // Sort file lists within each group by variant index, then build result
        TreeMap<String, List<CaptureGroup>> result = new TreeMap<>(numericStringComparator());

        for (Map.Entry<String, Map<String, CaptureGroup>> subjEntry : accumulator.entrySet()) {
            List<CaptureGroup> groups = new ArrayList<>(subjEntry.getValue().values());

            // Sort groups by sessionID then trialNum numerically
            groups.sort((a, b) -> {
                int cmpSes = numericCompare(a.sessionID, b.sessionID);
                return cmpSes != 0 ? cmpSes : numericCompare(a.trialNum, b.trialNum);
            });

            // Sort each eye's file list by variant index
            for (CaptureGroup g : groups) {
                g.leftEyeFiles.sort((a, b) ->
                        Integer.compare(variantIndex(a.getName()), variantIndex(b.getName())));
                g.rightEyeFiles.sort((a, b) ->
                        Integer.compare(variantIndex(a.getName()), variantIndex(b.getName())));
            }

            result.put(subjEntry.getKey(), groups);
        }

        return result;
    }

    // -------------------------------------------------------------------------

    private static class ParsedFilename {
        String subjectID, sessionID, trialNum, eye;
        int variantIndex;
    }

    /**
     * Parses a filename like "001_1_2_left_3.png" into its components.
     * Returns null if the filename does not match the expected pattern.
     */
    private static ParsedFilename parseFilename(String filename) {
        // Strip ".png"
        if (!filename.endsWith(".png")) return null;
        String base = filename.substring(0, filename.length() - 4);

        String[] parts = base.split("_");
        // Minimum: subjectID_sessionID_trialNum_eye → 4 parts
        if (parts.length < 4) return null;

        // Find the index of "left" or "right"
        int eyeIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if ("left".equals(parts[i]) || "right".equals(parts[i])) {
                eyeIdx = i;
                break;
            }
        }
        if (eyeIdx < 0) return null;
        // We need exactly subjectID, sessionID, trialNum before the eye word
        if (eyeIdx < 3) return null;

        ParsedFilename pf = new ParsedFilename();
        pf.subjectID  = parts[eyeIdx - 3];
        pf.sessionID  = parts[eyeIdx - 2];
        pf.trialNum   = parts[eyeIdx - 1];
        pf.eye        = parts[eyeIdx];

        // Optional variant number after eye word
        if (parts.length > eyeIdx + 1) {
            try {
                pf.variantIndex = Integer.parseInt(parts[eyeIdx + 1]);
            } catch (NumberFormatException e) {
                return null; // unexpected trailing segment
            }
        } else {
            pf.variantIndex = 0; // base image
        }

        return pf;
    }

    /** Extracts the variant index from a filename (0 for base image). */
    private static int variantIndex(String filename) {
        if (!filename.endsWith(".png")) return 0;
        String base = filename.substring(0, filename.length() - 4);
        String[] parts = base.split("_");
        // Last part is variant number if numeric
        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Compares two strings numerically if both are valid integers, else lexicographically. */
    private static int numericCompare(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static Comparator<String> numericStringComparator() {
        return (a, b) -> numericCompare(a, b);
    }
}
