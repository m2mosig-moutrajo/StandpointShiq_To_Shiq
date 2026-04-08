package org.standpoint.plugin.ui;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class StandpointPluginActivator implements BundleActivator {

    @Override
    public void start(BundleContext context) throws Exception {
        System.out.println("Standpoint Plugin started!");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        System.out.println("Standpoint Plugin stopped!");
    }
}