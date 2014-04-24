/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.jenkinsci.plugins.pretestedintegration.scm.git;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.git.Branch;
import hudson.plugins.git.util.BuildData;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviour;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviourDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Mads
 */
public class DeleteIntegratedBranchBehaviour extends SCMPostBuildBehaviour {

    @DataBoundConstructor
    public DeleteIntegratedBranchBehaviour() { }

    @Override
    public void applyBehaviour(AbstractBuild build, Launcher launcher, BuildListener listener) {
        listener.getLogger().println("Applying behaviiour");
        try {
            if(build != null && build.getResult().isBetterOrEqualTo(Result.SUCCESS)) {  
                GitClient client = Git.with(listener, build.getEnvironment(listener)).in(build.getWorkspace()).getClient();
                BuildData gitBuildData = build.getAction(BuildData.class);            
                String gitDataBranch = gitBuildData.lastBuild.revision.getBranches().iterator().next().getName();
                client.deleteBranch(gitDataBranch);                
            }
        } catch (IOException ex) {
            Logger.getLogger(DeleteIntegratedBranchBehaviour.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            Logger.getLogger(DeleteIntegratedBranchBehaviour.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    }
    
    @Extension
    public static final class DescriptorImpl extends SCMPostBuildBehaviourDescriptor<DeleteIntegratedBranchBehaviour> {

        public DescriptorImpl() {
            load();
        }
        
        public String getDisplayName() {
            return "Delete integrated branch on succesful merge";
        }
    }
    
}
