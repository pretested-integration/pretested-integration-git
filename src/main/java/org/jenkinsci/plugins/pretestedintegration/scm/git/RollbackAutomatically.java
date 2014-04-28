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
import java.io.IOException;
import org.jenkinsci.plugins.pretestedintegration.AbstractSCMBridge;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviour;
import org.jenkinsci.plugins.pretestedintegration.SCMPostBuildBehaviourDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Mads
 */
public class RollbackAutomatically extends SCMPostBuildBehaviour {
    
    private static final String B_NAME = "Rollback integration branch on build failure";
    
    @DataBoundConstructor
    public RollbackAutomatically() { }

    @Override
    public void applyBehaviour(AbstractBuild build, Launcher launcher, BuildListener listener, AbstractSCMBridge bridge) throws IOException, InterruptedException {
        listener.getLogger().println(String.format("Applying behaviour '%s'", B_NAME));
        Result result = build.getResult();
        if(result != null && !result.isBetterOrEqualTo(bridge.getRequiredResult())) {
            bridge.rollback(build, launcher, listener);
        }
    }
    
    @Extension
    public static final class DescriptorImpl extends SCMPostBuildBehaviourDescriptor<RollbackAutomatically> {
        
        public DescriptorImpl() {
            load();
        }
        
        @Override
        public String getDisplayName() {
            return B_NAME;
        }
        
    }
    
}
