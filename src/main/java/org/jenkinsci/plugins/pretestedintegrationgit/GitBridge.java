package org.jenkinsci.plugins.pretestedintegrationgit;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.util.ArgumentListBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.jenkinsci.plugins.pretestedintegration.AbstractSCMBridge;
import org.jenkinsci.plugins.pretestedintegration.Commit;
import org.jenkinsci.plugins.pretestedintegration.SCMBridgeDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

public class GitBridge extends AbstractSCMBridge {

	private boolean reset;
	private String revId;
	private String branches;
	//private String result;
	
	@DataBoundConstructor
	public GitBridge(boolean reset, String branches, String branch){
		this.reset = reset;
		this.branches = branches;
		if(branch != null && !branch.equals(""))
			this.branch = branch;
	}
	
	public boolean getReset(){
		return this.reset;
	}
	
	public String getBranches() {
		return this.branches;
	}
	
	public String getBranch() {
		return this.branch == null ? "default" : this.branch;
	}
	
	public String getRevId() {
		return this.revId;
	}
	/*
	public String getResult() {
		return this.result;
	}*/
	
	/**
	 * The directory in which to execute git commands
	 */
	private FilePath workingDirectory = null;
	final static String LOG_PREFIX = "[PREINT-GIT] ";

	public void setWorkingDirectory(FilePath workingDirectory){
		this.workingDirectory = workingDirectory;
	}
	
	public FilePath getWorkingDirectory(){
		return this.workingDirectory;
	}

    private GitSCM findScm(AbstractBuild<?,?> build) throws InterruptedException {
        try{
            SCM scm = build.getProject().getScm();
            GitSCM git = (GitSCM) scm;
            return git;
        } catch (ClassCastException e) {
            throw new InterruptedException("Configured scm is not mercurial");
        }
    }
    
    private ProcStarter buildCommand(AbstractBuild<?,?> build, Launcher launcher, TaskListener listener, String... cmds) throws IOException, InterruptedException {
    	GitSCM scm = findScm(build);
    	
    	String gitExe = scm.getGitExe(build.getBuiltOn(), listener);
    	
        ArgumentListBuilder b = new ArgumentListBuilder();
        b.add(gitExe);
        b.add(cmds);
        return launcher.launch().cmds(b).pwd(build.getWorkspace());
    }
	
	/**
	 * Invoke a command with mercurial
	 * @param build
	 * @param launcher
	 * @param listener
	 * @param cmds
	 * @return The exitcode of command
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public int git(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener, String... cmds) throws IOException, InterruptedException{
		ProcStarter git = buildCommand(build, launcher, listener,cmds);
		int exitCode = git.join();
		return exitCode;
	}

	/**
	 * Invoke a command with mercurial
	 * @param build
	 * @param launcher
	 * @param listener
	 * @param cmds
	 * @return The exitcode of command
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public int git(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener,OutputStream out, String... cmds) throws IOException, InterruptedException{
		ProcStarter git = buildCommand(build, launcher, listener,cmds);
		int exitCode = git.stdout(out).join();
		return exitCode;
	}
	
	@Override
	protected void ensureBranch(AbstractBuild<?,?> build, Launcher launcher, TaskListener listener, String branch) 
		throws IOException, InterruptedException {

		logger.finest("Updating the position to the integration branch");
		//Make sure that we are on the integration branch
		git(build, launcher, listener, "checkout", getBranch());
	}
	
	@Override
	protected void mergeChanges(AbstractBuild<?,?> build, Launcher launcher, TaskListener listener, Commit<?> commit) throws IOException, InterruptedException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// Checkout master
		int exitCode = git(build, launcher, listener, out, "merge","--squash", (String) commit.getId());
		if(exitCode > 0){
			logger.finest("git command failed with exitcode: " + exitCode);
			throw new AbortException("Could not merge. Git output: " + out.toString());
		}
	}
	
	public String integrationTip(AbstractBuild<?,?> build, Launcher launcher, TaskListener listener, 
			Commit<?> commit) throws IOException, InterruptedException {
		String revision = "0";
		if(commit == null || reset) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			logger.finest("Resetting revision to last successful build");
			
			
			//Get the last build on the integration branch
			git(build,launcher, listener, out, "log", "origin/" + branch, "-n", "1", "--format=%H");
			revision = out.toString().trim();
		} else {
			logger.finest("Setting revision to previous build");
			revision = (String) commit.getId();
		}
		listener.getLogger().println("Base revisions is:" + revision);
		return revision;
	}
	
	protected void update(AbstractBuild<?,?> build, Launcher launcher, 
			TaskListener listener) throws IOException, InterruptedException {
		//
		//ensure that we have the latest version of the integration branch
		//TODO: hardcoded origin is bad :(
		git(build, launcher, listener, "fetch", "origin", branch);
	}
	
	protected List<String> revisions(AbstractBuild<?,?> build, Launcher launcher, 
			TaskListener listener, Commit<?> commit) throws InterruptedException, IOException {
		List<String> revisions = new ArrayList<String>();
		try{ 
			Commit<String> gitCommit = (Commit<String>) commit;
			String localBranch = getBranch();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			String base = integrationTip(build, launcher, listener, commit);
			String revSpec = "HEAD";
			if(!(base.equals(""))){
				revSpec = base + "..HEAD";
			}
			
			int exitCode = git(build, launcher, listener, out, "log", revSpec, "--reverse","--format=%H");
			String[] commits = out.toString().trim().split("\\n");
			
			if(exitCode == 0 && commits.length > 0) {
				for(String c : commits) {
					revisions.add(c.trim());
				}
			}
		} catch (ClassCastException e) {
			throw new IOException(LOG_PREFIX + "Commit not recognised as GitCommit" + e.getMessage());
		}
		return revisions;
	}
	
	protected Commit<String> getNext(List<?> revisions) {
		Commit<String> next = null;
		if(revisions.size() > 0 && !revisions.get(0).toString().equals("")) {
			revId = revisions.get(0).toString();
			next = new Commit<String>(revId);
		}
		return next;
	}
	
	/**
	 * 1. Convert the stuff in the commit to Map<String,String>
	 * 2. Check the current working branch if there are any more commits in that branch
	 * 3. Check the next branch round-robin
	 * 
	 */
	@Override
	public Commit<String> nextCommit(
			AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, Commit<?> commit)
			throws IOException, IllegalArgumentException{
		logger.finest("Git plugin, nextCommit invoked");
		Commit<String> next = null;
		try {
			Commit<String> gitCommit = (Commit<String>) commit;
			
			//Make sure that we have the latest changes before doing anything
			update(build, launcher, listener);

			List<?> revisions = revisions(build, launcher, listener, commit);
			
			if(revisions.size() < 1) {
			
			//we wish to find the next branch
			//Retrieve a list of all branches
			
			}
			next = getNext(revisions);
			if(next != null)
				listener.getLogger().println(LOG_PREFIX + "next revision is:" + next.getId().toString());
		} catch (InterruptedException e){
			throw new IOException(e.getMessage());
		} catch (ClassCastException e) {
			logger.finest("Configured scm is not git. Aborting...");
		}
		this.reset = false;
		logger.finest("Git plugin, nextCommit returning");
		return next;
	}

	@Override
	public void commit(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		logger.finest("Git plugin commiting");
		git(build, launcher, listener,"commit","-m", "Integrated revision " + revId);
		
		//push the changes back to the repo
		git(build, launcher, listener,"push");
	}

	@Override
	public void rollback(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		logger.finest("Git plugin rolling back");
		git(build, launcher, listener, "reset", "--hard", "HEAD");
	}
	
	@Extension
	public static final class DescriptorImpl extends SCMBridgeDescriptor<GitBridge> {
		
		public String getDisplayName(){
			return "Git";
		}
		
		@Override
		public GitBridge newInstance(StaplerRequest req, JSONObject formData) throws FormException {
			GitBridge i = (GitBridge) super.newInstance(req, formData);
			
			
			String branch = formData.getJSONObject("scmBridge").getString("branch");
			
			i.reset = false;
			i.branch = branch;
			
			save();
			return i;
		}
	}
	
	private static Logger logger = Logger.getLogger(GitBridge.class.getName());
}
