/**********************************************************************
 * Copyright (c) 2003 IBM Corporation and others. All rights reserved.   This
 * program and the accompanying materials are made available under the terms of
 * the Common Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: 
 * IBM - Initial API and implementation
 **********************************************************************/
package org.eclipse.core.internal.resources;

import org.eclipse.core.internal.utils.Policy;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;

/**
 * The job for performing workspace auto-builds.
 */
class AutoBuildJob extends Job {
	private boolean buildNeeded = false;
	private boolean forceBuild = false;

	private final Workspace workspace;
	AutoBuildJob(Workspace workspace) {
		super(ICoreConstants.MSG_EVENTS_BUILDING_0);
		this.workspace = workspace;
	}
	/**
	 * Used to prevent auto-builds at the end of operations that contain explicit builds
	 */
	public synchronized void avoidBuild() {
		buildNeeded = false;
	}
	public synchronized void checkCancel() {
		int state = getState();
		//cancel the build job if it is waiting to run
		if (state == Job.WAITING)  {
			cancel();
			return;
		}
		//cancel the build job if another job is attempting to modify the workspace
		//while the build job is running
		if (state == Job.RUNNING && Platform.getJobManager().currentJob() != this) 
			workspace.getBuildManager().interrupt();
	}
	private void doBuild(IProgressMonitor monitor) throws CoreException, OperationCanceledException {
		monitor = Policy.monitorFor(monitor);
		try {
			monitor.beginTask(null, Policy.opWork);
			try {
				workspace.prepareOperation();
				workspace.beginOperation(true);
				workspace.broadcastChanges(IResourceChangeEvent.PRE_AUTO_BUILD, false);
				if (workspace.isAutoBuilding())
					workspace.getBuildManager().build(IncrementalProjectBuilder.AUTO_BUILD, Policy.subMonitorFor(monitor, Policy.opWork));
				workspace.broadcastChanges(IResourceChangeEvent.POST_AUTO_BUILD, true);
			} finally {
				//building may close the tree, but we are still inside an operation so open it
				if (workspace.getElementTree().isImmutable())
					workspace.newWorkingTree();
				avoidBuild();
				workspace.endOperation(false, Policy.subMonitorFor(monitor, Policy.buildWork));
			}
		} finally {
			monitor.done();
		}
	}
	public synchronized void endTopLevel(boolean needsBuild) {
		buildNeeded |= needsBuild;
		if (shouldBuild())
			schedule(Policy.AUTO_BUILD_DELAY);
	}
	/**
	 * Forces a build to occur at the end of the next top level operation.  This is
	 * used when workspace description changes neccessitate a build regardless
	 * of the tree state.
	 */
	public synchronized void forceBuild() {
		forceBuild = true;
	}
	public IStatus run(IProgressMonitor monitor) {
		//synchronized in case build starts during checkCancel
		synchronized (this)  {
			if (monitor.isCanceled())
				return Status.CANCEL_STATUS;
		}
		try {
			//clear build flags
			forceBuild = buildNeeded = false;
			doBuild(monitor);
			return Status.OK_STATUS;
		} catch (OperationCanceledException e) {
			buildNeeded = true;
			return Status.CANCEL_STATUS;
		} catch (CoreException sig) {
			return sig.getStatus();
		}
	}
	private synchronized boolean shouldBuild() {
		//build if the workspace requires a build (description changes)
		if (forceBuild)
			return true;
		//return whether there have been any changes to the workspace tree.
		return buildNeeded;
	}
}