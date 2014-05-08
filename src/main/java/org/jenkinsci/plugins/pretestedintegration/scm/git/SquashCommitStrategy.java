/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.jenkinsci.plugins.pretestedintegration.scm.git;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.util.BuildData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.jenkinsci.plugins.pretestedintegration.AbstractSCMBridge;
import org.jenkinsci.plugins.pretestedintegration.Commit;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategy;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
/**
 *
 * @author Mads
 */
public class SquashCommitStrategy extends IntegrationStrategy {
    
    private static final String B_NAME = "Squashed commit";
    
    @DataBoundConstructor
    public SquashCommitStrategy() { }

    @Override
    public void integrate(AbstractBuild build, Launcher launcher, BuildListener listener, AbstractSCMBridge bridge, Commit<?> commit) throws IOException, InterruptedException {
        int exitCode = -999;
        int exitCodeCommit = -999;
        GitBridge gitbridge = (GitBridge)bridge;
        
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BuildData gitBuildData = build.getAction(BuildData.class);
        Branch gitDataBranch = gitBuildData.lastBuild.revision.getBranches().iterator().next();        
        listener.getLogger().println( String.format( "Preparing to merge changes in commit %s to integration branch %s", (String) commit.getId(), bridge.getBranch() ) );

        exitCode = gitbridge.git(build, launcher, listener, out, "merge", "--squash", gitDataBranch.getName());
        exitCodeCommit = gitbridge.git(build, launcher, listener, out, "commit", "-m", String.format("Integrated %s", gitDataBranch.getName()));
        
        if (exitCode > 0) {
            listener.getLogger().println("Failed to merge changes. Error message below");
            listener.getLogger().println(out.toString());
            throw new AbortException("Could not merge. Git output: " + out.toString());
        }
        
        if (exitCodeCommit != 0 && exitCodeCommit != -999 ) {
            listener.getLogger().println("Failed to commit merged changes. Error message below");
            listener.getLogger().println(out.toString());
            throw new AbortException("Could commit merges. Git output: " + out.toString());
        }

        /*
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GitBridge gitBridge = (GitBridge)bridge;
        BuildData gitBuildData = build.getAction(BuildData.class);
        Branch gitDataBranch = gitBuildData.lastBuild.revision.getBranches().iterator().next();
        
        if(build != null && build.getResult().isBetterOrEqualTo(bridge.getRequiredResult())) {
            listener.getLogger().println(String.format("Applying behaviour '%s'", B_NAME));
            int delRemote = gitBridge.git(build, launcher, listener, out, "push", "origin",":"+removeOrigin(gitDataBranch.getName()));
            if(delRemote != 0) {
                throw new IOException(String.format( "Failed to delete the remote branch %s with the following error:%n%s", gitBridge.getBranch(), out.toString()) );
            } 
        }
        */
    }
    
    private String removeOrigin(String branchName) {
        String s = branchName.substring(branchName.indexOf("/")+1, branchName.length());
        return s;
    }
    
    @Extension
    public static final class DescriptorImpl extends IntegrationStrategyDescriptor<SquashCommitStrategy> {

        public DescriptorImpl() {
            load();
        }
        
        public String getDisplayName() {
            return B_NAME;
        }   
    }
    
}
