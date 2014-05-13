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
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.pretestedintegration.AbstractSCMBridge;
import org.jenkinsci.plugins.pretestedintegration.Commit;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategy;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategyDescriptor;
import org.jenkinsci.plugins.pretestedintegration.NothingToDoException;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Mads
 */
public class AccumulatedCommitStrategy extends IntegrationStrategy {
    
    private static final String B_NAME = "Accumulated commit";
    
    @DataBoundConstructor
    public AccumulatedCommitStrategy() { }

    @Override
    public void integrate(AbstractBuild build, Launcher launcher, BuildListener listener, AbstractSCMBridge bridge, Commit<?> commit) throws IOException, InterruptedException {
        int exitCode = -999;
        GitBridge gitbridge = (GitBridge)bridge;
        
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BuildData gitBuildData = build.getAction(BuildData.class);
        Branch gitDataBranch = gitBuildData.lastBuild.revision.getBranches().iterator().next();        
        
        GitClient client = Git.with(listener, build.getEnvironment(listener)).in(build.getWorkspace()).getClient();
        
        boolean found = false;
        for(Branch b : client.getRemoteBranches()) {
            if(b.getName().equals(gitDataBranch.getName())) {
                listener.getLogger().println("Branch:"+b.getName());
                found = true;
                break;
            }
        }
        
        if(!found) {
            build.setDescription(String.format("Noting to do"));
            throw new NothingToDoException();
        }

        listener.getLogger().println( String.format( "Preparing to merge changes in commit %s to integration branch %s", (String) commit.getId(), gitbridge.getBranch() ) );            
        exitCode = gitbridge.git(build, launcher, listener, out, "merge","-m", String.format("Integrated %s", gitDataBranch.getName()), (String) commit.getId(), "--no-ff");
        
        if (exitCode > 0) {
            listener.getLogger().println("Failed to merge changes.");
            listener.getLogger().println(out.toString());
            build.setDescription(String.format("Merge conflict"));
            throw new IOException("Could not merge. Git output: " + out.toString());
        }
        
    }
    
    @Extension
    public static final class DescriptorImpl extends IntegrationStrategyDescriptor<AccumulatedCommitStrategy> {
        
        public DescriptorImpl() {
            load();
        }
        
        @Override
        public String getDisplayName() {
            return B_NAME;
        }
        
    }
    
}
