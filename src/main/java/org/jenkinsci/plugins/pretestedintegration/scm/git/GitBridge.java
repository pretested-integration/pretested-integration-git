package org.jenkinsci.plugins.pretestedintegration.scm.git;

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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import org.jenkinsci.plugins.pretestedintegration.AbstractSCMBridge;
import org.jenkinsci.plugins.pretestedintegration.Commit;
import org.jenkinsci.plugins.pretestedintegration.PretestedIntegrationAction;
import org.jenkinsci.plugins.pretestedintegration.SCMBridgeDescriptor;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategy;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class GitBridge extends AbstractSCMBridge {

    private String revId; 
    private boolean deleteDevelopmentBranch;
    private String mergeOption;

    @DataBoundConstructor
    public GitBridge(IntegrationStrategy integrationStrategy, final String branch, String mergeOption) {
        super(integrationStrategy);        
        this.branch = branch;  
        this.mergeOption = mergeOption;
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
    protected void ensureBranch(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, String branch) throws IOException, InterruptedException {
        GitClient gitclient = Git.with(listener, build.getEnvironment(listener)).in(build.getWorkspace()).getClient();        
        logger.finest("Updating the position to the integration branch");
        gitclient.checkout().branch(getBranch()).execute();        
    }

    protected void update(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {		
        //ensure that we have the latest version of the integration branch
        logger.finest( String.format( "Fetching latest version of integraion branch: %s", branch) );        
        git(build, launcher, listener, "fetch", "origin", branch);
    }
    
    /**
     * 1. Convert the stuff in the commit to Map<String,String>
     * 2. Check the current working branch if there are any more commits in that
     * branch 3. Check the next branch round-robin
     *
     * @return 
     * @throws java.io.IOException
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
            next = new Commit<String>(gitDataBranch.getSHA1String());
        } catch (InterruptedException e) {
            throw new IOException(e.getMessage());
        } catch (ClassCastException e) {
            logger.finest("Configured scm is not git. Aborting...");
        }
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
        int returncode = -9999;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        
        Commit<?> lastIntegraion = build.getAction(PretestedIntegrationAction.class).getCurrentIntegrationTip();
        if(lastIntegraion != null) {
            returncode = git(build, launcher, listener, bos, "reset", "--hard", (String)lastIntegraion.getId());
        }
        
        if(returncode != 0) {
            if(returncode == -9999) {
                throw new IOException("Failed to rollback changes, because the integraion tip could not be determined"); 
            }
            throw new IOException( String.format( "Failed to rollback changes, message was:%n%s", bos.toString()) );
        }        
    }

    @Override
    protected Commit<?> determineIntegrationHead(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener) {
        Commit<?> commit = null;
        try {
            GitClient client = Git.with(listener, build.getEnvironment(listener)).in(build.getWorkspace()).getClient();
            for(Branch b : client.getBranches()) {
                if(b.getName().contains(getBranch())) {
                    commit = new Commit(b.getSHA1String());
                }
            }            
        } catch (IOException ex) {
            Logger.getLogger(GitBridge.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            Logger.getLogger(GitBridge.class.getName()).log(Level.SEVERE, null, ex);
        }
        return commit;
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

    /**
     * @return the mergeOption
     */
    public String getMergeOption() {
        return mergeOption;
    }

    /**
     * @param mergeOption the mergeOption to set
     */
    public void setMergeOption(String mergeOption) {
        this.mergeOption = mergeOption;
    }
    
    @Extension
    public static final class DescriptorImpl extends SCMBridgeDescriptor<GitBridge> {

        public DescriptorImpl() {
            load();
        }
        
        public String getDisplayName() {
            return "Git";
        }
        
        public static List<IntegrationStrategyDescriptor<?>> getIntegrationStrategies() {
            List<IntegrationStrategyDescriptor<?>> list = new ArrayList<IntegrationStrategyDescriptor<?>>();
            for(IntegrationStrategyDescriptor<?> descr : IntegrationStrategy.all()) {
               list.add(descr);
            }        
            return list;
        }
        
        public IntegrationStrategy getDefaultStrategy() {            
            return new SquashCommitStrategy();
        }

    }

    private static final Logger logger = Logger.getLogger(GitBridge.class.getName());
}
