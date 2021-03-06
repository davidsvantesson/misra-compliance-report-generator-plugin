package io.jenkins.plugins.rortveiten;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletException;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import rortveiten.misra.WarningParser;
import rortveiten.misra.WarningParser.MisraVersion;

/*
 * TODO:
 * 
 * More elegant error handling
 * Support for using several tools simultaneously
 * Read suppressions from a separate file
 * 
 * Number of source files?
 * Number of violations for each guideline?
 * Advanced -> List of violations/deviations with file/line number?
 * 
 */

public class MisraGcsBuilderPlugin extends Recorder implements SimpleBuildStep {
    /* GRP = Guideline Re-categorization Plan */
    private String grpFile;
    private String warningsFile, sourceListFile;
    private String warningParser;
    private String ruleSet;
    private boolean doFailOnError;
    private boolean doFailOnIncompliance;
    private String nonMisraTagPattern;
    private String projectName;
    private String softwareVersion;
    private String logFile = "";

    @DataBoundConstructor
    public MisraGcsBuilderPlugin() {
    }

    @DataBoundSetter
    public void setWarningsFile(String warningsFile) {
        this.warningsFile = warningsFile;
    }

    @DataBoundSetter
    public void setSourceListFile(String sourceListFile) {
        this.sourceListFile = sourceListFile;
    }

    @DataBoundSetter
    public void setGrpFile(String grpFile) {
        this.grpFile = grpFile;
    }

    @DataBoundSetter
    public void setDoFailOnError(boolean doFailOnError) {
        this.doFailOnError = doFailOnError;
    }

    @DataBoundSetter
    public void setDoFailOnIncompliance(boolean doFailOnIncompliance) {
        this.doFailOnIncompliance = doFailOnIncompliance;
    }

    @DataBoundSetter
    public void setWarningParser(String warningParser) {
        this.warningParser = warningParser;
    }

    @DataBoundSetter
    public void setRuleSet(String ruleSet) {
        this.ruleSet = ruleSet;
    }

    @DataBoundSetter
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @DataBoundSetter
    public void setSoftwareVersion(String softwareVersion) {
        this.softwareVersion = softwareVersion;
    }

    public String getSoftwareVersion() {
        return softwareVersion;
    }

    public String getProjectName() {
        return projectName;
    }

    public boolean isDoFailOnError() {
        return doFailOnError;
    }

    public boolean isDoFailOnIncompliance() {
        return doFailOnIncompliance;
    }

    public String getWarningParser() {
        return warningParser;
    }

    public String getWarningsFile() {
        return warningsFile;
    }

    public String getGrpFile() {
        return grpFile;
    }

    public String getSourceListFile() {
        return sourceListFile;
    }

    public String getRuleSet() {
        return ruleSet;
    }

    private static WarningParser findWarningParser(String name) {
        ExtensionList<WarningParser> parsers = WarningParser.all();
        for (WarningParser parser : parsers) {
            if (name.equals(parser.name())) {
                return parser;
            }
        }
        return null;
    }

    private List<String> readAllLines(FilePath file, Run<?, ?> run, PrintStream logger) {
        List<String> ret = new ArrayList<String>(0);
        try {
            String content = file.readToString();
            if (!content.isEmpty())
                ret = Arrays.asList(content.split("[\\r?\\n]+"));
            else
                logger.println("Misra GCS plugin: Warning: No source files to process. \"" + file + "\" is empty.");
        } catch (IOException | InterruptedException ex) {
            logger.println("Misra GCS plugin: File not found: " + file);
            run.setResult(Result.FAILURE);
        }
        return ret;
    }

    private static boolean isCompatible(String warningParser, String ruleSet) {
        MisraVersion version = MisraVersion.fromString(ruleSet);
        WarningParser parser = findWarningParser(warningParser);
        return parser.supportedMisraVersions().contains(version);
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        if (!isCompatible(warningParser, ruleSet)) {
            listener.getLogger().println(
                    "Misra GCS plugin: " + ruleSet + " is not supported by " + warningParser  + ". Build failed.");
            run.setResult(Result.FAILURE);
            return;
        }
        MisraVersion misraVersion = MisraVersion.fromString(ruleSet);
        WarningParser parser = findWarningParser(warningParser);
        parser.initialize(misraVersion);
        parser.setWorkspace(workspace);
        parser.setLogger(listener.getLogger());
        if (logFile == null || logFile.isEmpty())
            parser.setLogFilePath(null);
        else
            parser.setLogFilePath(logFile);
        EnvVars env = run.getEnvironment(listener);

        if (grpFile != null && !grpFile.isEmpty())
            parser.readGrp(readAllLines(workspace.child(grpFile), run, listener.getLogger()));

        List<String> warningLines = readAllLines(workspace.child(warningsFile), run, listener.getLogger());
        List<String> sourceFiles = relativePaths(
                readAllLines(workspace.child(sourceListFile), run, listener.getLogger()), workspace);
        if (run.getResult() == Result.FAILURE)
            return;

        parser.parseWarnings(warningLines);
        parser.parseSourceFiles(sourceFiles);

        if (parser.getErrorCode() != 0 && doFailOnError) {
            listener.getLogger()
                    .println("Misra GCS plugin: Build failed because an error occurred during creation of GCS");
            run.setResult(Result.FAILURE);
        } else if (!parser.isCompliant() && doFailOnIncompliance) {
            listener.getLogger().println("Misra GCS plugin: Build failed because the code is not MISRA compliant");
            run.setResult(Result.FAILURE);
        }

        String notes = parser.getErrorCode() == 0 ? ""
                : "Errors occured during processing. This report is not valid. See console output for details.";
        String _softwareVersion = env.expand(softwareVersion);
        String _projectName = env.expand(projectName);
        GcsAction action = new GcsAction(run, parser.getGuidelines(), warningParser, _softwareVersion, _projectName,
                misraVersion.toString(), parser.isCompliant(), parser.name(), parser.summary(), notes);
        run.addAction(action);
    }

    protected static List<String> relativePaths(List<String> paths, FilePath relativeTo)
            throws IOException, InterruptedException {
        List<String> relative = new ArrayList<String>(paths.size());
        URI here = relativeTo.absolutize().toURI();
        for (String path : paths) {
            File f = new File(path);
            if (f.isAbsolute()) {
                URI absPath = f.toURI();
                relative.add(here.relativize(absPath).toString());
            } else
                relative.add(path);
        }
        return relative;
    }

    @Extension
    @Symbol("misraReport")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.MisraGcsBuilderPlugin_DescriptorImpl_DisplayName();
        }

        public ListBoxModel doFillWarningParserItems() {
            ListBoxModel items = new ListBoxModel();
            ExtensionList<WarningParser> parsers = WarningParser.all();
            for (WarningParser parser : parsers) {
                items.add(parser.name(), parser.name());
            }
            return items;
        }

        public ListBoxModel doFillRuleSetItems() {
            ListBoxModel items = new ListBoxModel();
            for (MisraVersion version : MisraVersion.values())
                items.add(version.toString(), version.toString());
            return items;
        }

        public FormValidation doCheckWarningsFile(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0) {
                return FormValidation.error("Required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckSourceListFile(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0) {
                return FormValidation.error("Required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRuleSet(@QueryParameter String value, @QueryParameter String warningParser) {
            
            if ("".equals(value) || "".equals(warningParser)) { //A bug in jenkins? At initial validation, both values are empty
                value = MisraVersion.values()[0].toString();
                warningParser = WarningParser.all().get(0).name();
            }
                
            if (!isCompatible(warningParser, value))
                return FormValidation.error(value + " is not supported by " + warningParser);
            return FormValidation.ok();
        }
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public String getNonMisraTagPattern() {
        return nonMisraTagPattern;
    }

    @DataBoundSetter
    public void setNonMisraTagPattern(String nonMisraTagPattern) {
        this.nonMisraTagPattern = nonMisraTagPattern;
    }

    public String getLogFile() {
        return logFile;
    }

    @DataBoundSetter
    public void setLogFile(String logFile) {
        this.logFile = logFile;
    }

}
