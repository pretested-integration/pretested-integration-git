package org.jenkinsci.plugins.pretestedintegration.scm.git;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.util.BuildData;
import hudson.scm.SCM;
import hudson.util.ArgumentListBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;

import org.jenkinsci.plugins.pretestedintegration.AbstractSCMBridge;
import org.jenkinsci.plugins.pretestedintegration.Commit;
import org.jenkinsci.plugins.pretestedintegration.SCMBridgeDescriptor;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviour;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviourDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class GitBridge extends AbstractSCMBridge {

    private boolean reset;
    private String revId; 
    private boolean deleteDevelopmentBranch;
    
    @DataBoundConstructor
    public GitBridge(boolean reset, final String branch, boolean deleteDevelopmentBranch) {
        this.reset = reset;
        this.branch = branch;
        this.deleteDevelopmentBranch = deleteDevelopmentBranch;
    }

    public boolean getReset() {
        return this.reset;
    }
    
    public void setReset(boolean reset) {
        this.reset = reset;
    }
    
    @Override
    public String getBranch() {
        return StringUtils.isBlank(this.branch) ? "master" : this.branch;
    }

    public String getRevId() {
        return this.revId;
    }
    
    /**
     * The directory in which to execute git commands
     */
    private FilePath workingDirectory = null;
    final static String LOG_PREFIX = "[PREINT-GIT] ";

    public void setWorkingDirectory(FilePath workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public FilePath getWorkingDirectory() {
        return this.workingDirectory;
    }

    private GitSCM findScm(AbstractBuild<?, ?> build) throws InterruptedException {
        try {
            SCM scm = build.getProject().getScm();
            GitSCM git = (GitSCM) scm;
            return git;
        } catch (ClassCastException e) {
            throw new InterruptedException("Configured scm is not Git");
        }
    }

    private ProcStarter buildCommand(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, String... cmds) throws IOException, InterruptedException {
        GitSCM scm = findScm(build);        
        String gitExe = scm.getGitExe(build.getBuiltOn(), listener);

        ArgumentListBuilder b = new ArgumentListBuilder();
        b.add(gitExe);
        b.add(cmds);
        return launcher.launch().cmds(b).pwd(build.getWorkspace());
    }

    /**
     * Invoke a command with git
     *
     * @param build
     * @param launcher
     * @param listener
     * @param cmds
     * @return The exitcode of command
     * @throws IOException
     * @throws InterruptedException
     */
    public int git(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, String... cmds) throws IOException, InterruptedException {
        ProcStarter git = buildCommand(build, launcher, listener, cmds);                
        int exitCode = git.join();
        
        
        return exitCode;
    }

    /**
     * Invoke a command with mercurial
     *
     * @param build
     * @param launcher
     * @param listener
     * @param cmds
     * @return The exitcode of command
     * @throws IOException
     * @throws InterruptedException
     */
    public int git(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, OutputStream out, String... cmds) throws IOException, InterruptedException {
        ProcStarter git = buildCommand(build, launcher, listener, cmds);
        int exitCode = git.stdout(out).join();
        return exitCode;
    }

    @Override
    protected void ensureBranch(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, String branch) throws IOException, InterruptedException {
        logger.finest("Updating the position to the integration branch");
        //Make sure that we are on the integration branch
        git(build, launcher, listener, "checkout", getBranch());
    }

    @Override
    protected void mergeChanges(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, Commit<?> commit) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        listener.getLogger().println( String.format( "Preparing to merge changes in commit %s to integration branch %s", (String) commit.getId(), getBranch() ) ); 
        int exitCode = git(build, launcher, listener, out, "merge","-m", String.format("Integrated %s", (String) commit.getId()), (String) commit.getId(), "--no-ff");
        if (exitCode > 0) {
            listener.getLogger().println("Failed to merge changes. Error message below");
            listener.getLogger().println(out.toString());
            throw new AbortException("Could not merge. Git output: " + out.toString());
        }
    }

    public String integrationTip(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, Commit<?> commit) throws IOException, InterruptedException {
        String revision = "0";
        if (commit == null || reset) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            logger.finest("Resetting revision to last successful build");
            git(build, launcher, listener, out, "log", "origin/" + branch, "-n", "1", "--format=%H");
            revision = out.toString().trim();
        } else {
            logger.finest("Setting revision to previous build");
            revision = (String) commit.getId();
        }
        listener.getLogger().println( String.format( "Base revisions is:%s for branch %s", revision, branch ) );
        return revision;
    }

    protected void update(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {		
        //ensure that we have the latest version of the integration branch
        logger.finest( String.format( "Fetching latest version of integraion branch: %s", branch) );        
        git(build, launcher, listener, "fetch", "origin", branch);
    }

    protected List<String> revisions(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, Commit<?> commit) throws InterruptedException, IOException {
        List<String> revisions = new ArrayList<String>();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String base = integrationTip(build, launcher, listener, commit);
            String revSpec = "HEAD";
            if (!(base.equals(""))) {
                revSpec = base + "..HEAD";
            }

            //int exitCode = git(build, launcher, listener, out, "log", revSpec, "--reverse", "--format=%H");
            int exitCode = git(build, launcher, listener, out, "log", revSpec, "--format=%H");
            String[] commits = out.toString().trim().split("\\n");

            if (exitCode == 0 && commits.length > 0) {
                for (String c : commits) {
                    if(!StringUtils.isBlank(c)) {
                        return Arrays.asList(c);
                    }
                }
            }
        } catch (ClassCastException e) {
            throw new IOException(LOG_PREFIX + "Commit not recognised as GitCommit" + e.getMessage());
        }
        return revisions;
    }

    protected Commit<String> getNext(Branch branch) {
        Commit<String> next = new Commit<String>(branch.getSHA1String());
        return next;
    }

    /**
     * 1. Convert the stuff in the commit to Map<String,String>
     * 2. Check the current working branch if there are any more commits in that
     * branch 3. Check the next branch round-robin
     *
     */
    @Override
    public Commit<String> nextCommit( AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, Commit<?> commit) throws IOException, IllegalArgumentException {
        logger.finest("Git plugin, nextCommit invoked");
        Commit<String> next = null;
        try {
            
            //Make sure that we have the latest changes before doing anything
            update(build, launcher, listener);
            BuildData gitBuildData = build.getAction(BuildData.class);
            Branch gitDataBranch = gitBuildData.lastBuild.revision.getBranches().iterator().next();
 
            next = getNext(gitDataBranch);
        } catch (InterruptedException e) {
            throw new IOException(e.getMessage());
        } catch (ClassCastException e) {
            logger.finest("Configured scm is not git. Aborting...");
        }
        this.reset = false;
        logger.finest("Git plugin, nextCommit returning");
        return next;
    }

    //FIXME: Yet another hardcoded origin
    @Override
    public void commit(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        logger.finest("Git pre-tested-commit commiting");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int returncode = git(build, launcher, listener, bos, "push", "origin", getBranch());
        if(returncode != 0) {
            throw new IOException( String.format( "Failed to commit integrated changes, message was:%n%s", bos.toString()) );
        }
    }

    @Override
    public void rollback(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        logger.finest("Git plugin rolling back");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int returncode = git(build, launcher, listener, bos, "reset", "--hard", "HEAD");
        if(returncode != 0) {
            throw new IOException( String.format( "Failed to commit integrated changes, message was:%n%s", bos.toString()) );
        }        
    }

    /**
     * @return the deleteDevelopmentBranch
     */
    public boolean isDeleteDevelopmentBranch() {
        return deleteDevelopmentBranch;
    }

    /**
     * @param deleteDevelopmentBranch the deleteDevelopmentBranch to set
     */
    public void setDeleteDevelopmentBranch(boolean deleteDevelopmentBranch) {
        this.deleteDevelopmentBranch = deleteDevelopmentBranch;
    }

    @Extension
    public static final class DescriptorImpl extends SCMBridgeDescriptor<GitBridge> {

        public DescriptorImpl() {
            load();
        }
        
        public String getDisplayName() {
            return "Git";
        }
        
        public static List<SCMPostBuildBehaviourDescriptor<?>> getBehaviours() {
            List<SCMPostBuildBehaviourDescriptor<?>> list = new ArrayList<SCMPostBuildBehaviourDescriptor<?>>();
            for(SCMPostBuildBehaviourDescriptor<?> descr : SCMPostBuildBehaviour.all()) {
               list.add(descr);
            }        
            return list;
        }
    }

    private static final Logger logger = Logger.getLogger(GitBridge.class.getName());
}
