package com.example.irisqualitycapture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents one capture session: a single (subjectID, sessionID, trialNum) triple,
 * containing the left-eye and right-eye image files sorted by variant index
 * (base image first, then _1, _2, _3 ...).
 */
public class CaptureGroup {
    public final String subjectID;
    public final String sessionID;
    public final String trialNum;
    public final List<File> leftEyeFiles;
    public final List<File> rightEyeFiles;

    public CaptureGroup(String subjectID, String sessionID, String trialNum) {
        this.subjectID = subjectID;
        this.sessionID = sessionID;
        this.trialNum  = trialNum;
        this.leftEyeFiles  = new ArrayList<>();
        this.rightEyeFiles = new ArrayList<>();
    }

    /** Total number of image files across both eyes. */
    public int totalImages() {
        return leftEyeFiles.size() + rightEyeFiles.size();
    }
}
