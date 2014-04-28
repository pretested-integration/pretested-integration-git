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
import hudson.plugins.git.Branch;
import hudson.plugins.git.util.BuildData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.jenkinsci.plugins.pretestedintegration.AbstractSCMBridge;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviour;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviourDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
/**
 *
 * @author Mads
 */
public class DeleteIntegratedBranchBehaviour extends SCMPostBuildBehaviour {

    private static final String B_NAME = "Delete integrated branch on success";
    
    @DataBoundConstructor
    public DeleteIntegratedBranchBehaviour() { }

    @Override
    public void applyBehaviour(AbstractBuild build, Launcher launcher, BuildListener listener, AbstractSCMBridge bridge) throws IOException, InterruptedException {
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
    }
    
    private String removeOrigin(String branchName) {
        String s = branchName.substring(branchName.indexOf("/")+1, branchName.length());
        return s;
    }
    
    @Extension
    public static final class DescriptorImpl extends SCMPostBuildBehaviourDescriptor<DeleteIntegratedBranchBehaviour> {

        public DescriptorImpl() {
            load();
        }
        
        public String getDisplayName() {
            return B_NAME;
        }
    }
    
}
