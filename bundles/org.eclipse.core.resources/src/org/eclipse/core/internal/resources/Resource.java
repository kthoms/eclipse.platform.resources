/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Dan Rubel <dan_rubel@instantiations.com> - Implementation of getLocalTimeStamp
 *     Red Hat Incorporated - get/setResourceAttribute code
 *******************************************************************************/
package org.eclipse.core.internal.resources;

import java.net.MalformedURLException;
import java.net.URL;
import org.eclipse.core.internal.events.LifecycleEvent;
import org.eclipse.core.internal.localstore.CoreFileSystemLibrary;
import org.eclipse.core.internal.localstore.FileSystemResourceManager;
import org.eclipse.core.internal.properties.PropertyManager;
import org.eclipse.core.internal.utils.*;
import org.eclipse.core.internal.watson.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.resources.team.IMoveDeleteHook;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.MultiRule;

public abstract class Resource extends PlatformObject implements IResource, ICoreConstants, Cloneable {
	/* package */IPath path;
	/* package */Workspace workspace;
	
	protected Resource(IPath path, Workspace workspace) {
		this.path = path.removeTrailingSeparator();
		this.workspace = workspace;
	}

	/* (non-Javadoc)
	 * @see IResource#accept(IResourceProxyVisitor, int)
	 */
	public void accept(final IResourceProxyVisitor visitor, int memberFlags) throws CoreException {
		final ResourceProxy proxy = new ResourceProxy();
		final boolean includePhantoms = (memberFlags & IContainer.INCLUDE_PHANTOMS) != 0;
		final boolean includeTeamPrivate = (memberFlags & IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS) != 0;
		IElementContentVisitor elementVisitor = new IElementContentVisitor() {
			public boolean visitElement(ElementTree tree, IPathRequestor requestor, Object contents) {
				ResourceInfo info = (ResourceInfo) contents;
				if (!includePhantoms && info.isSet(M_PHANTOM))
					return false;
				if (!includeTeamPrivate && info.isSet(M_TEAM_PRIVATE_MEMBER))
					return false;
				proxy.requestor = requestor;
				proxy.info = info;
				try {
					return visitor.visit(proxy);
				} catch (CoreException e) {
					//throw an exception to bail out of the traversal
					throw new WrappedRuntimeException(e);
				} finally {
					proxy.reset();
				}
			}
		};
		try {
			new ElementTreeIterator(workspace.getElementTree(), getFullPath()).iterate(elementVisitor);
		} catch (WrappedRuntimeException e) {
			throw (CoreException) e.getTargetException();
		} catch (OperationCanceledException e) {
			throw e;
		} catch (RuntimeException e) {
			String msg = Messages.resources_errorVisiting;
			IResourceStatus errorStatus = new ResourceStatus(IResourceStatus.INTERNAL_ERROR, getFullPath(), msg, e);
			ResourcesPlugin.getPlugin().getLog().log(errorStatus);
			throw new ResourceException(errorStatus);
		} finally {
			proxy.requestor = null;
			proxy.info = null;
		}
	}

	/* (non-Javadoc)
	 * @see IResource#accept(IResourceVisitor)
	 */
	public void accept(IResourceVisitor visitor) throws CoreException {
		accept(visitor, IResource.DEPTH_INFINITE, 0);
	}

	/* (non-Javadoc)
	 * @see IResource#accept(IResourceVisitor, int, boolean)
	 */
	public void accept(IResourceVisitor visitor, int depth, boolean includePhantoms) throws CoreException {
		accept(visitor, depth, includePhantoms ? IContainer.INCLUDE_PHANTOMS : 0);
	}

	/* (non-Javadoc)
	 * @see IResource#accept(IResourceVisitor, int, int)
	 */
	public void accept(final IResourceVisitor visitor, int depth, int memberFlags) throws CoreException {
		// it is invalid to call accept on a phantom when INCLUDE_PHANTOMS is not specified
		final boolean includePhantoms = (memberFlags & IContainer.INCLUDE_PHANTOMS) != 0;
		ResourceInfo info = getResourceInfo(includePhantoms, false);
		int flags = getFlags(info);
		checkExists(flags, true);
		//use the fast visitor if visiting to infinite depth
		if (depth == IResource.DEPTH_INFINITE) {
			accept(new IResourceProxyVisitor() {
				public boolean visit(IResourceProxy proxy) throws CoreException {
					return visitor.visit(proxy.requestResource());
				}
			}, memberFlags);
			return;
		}
		// ignore team private member entry point when INCLUDE_TEAM_PRIVATE_MEMBERS is not specified
		final boolean includeTeamPrivateMembers = (memberFlags & IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS) != 0;
		if (!includeTeamPrivateMembers && isTeamPrivateMember(flags))
			return;
		// visit this resource		
		if (!visitor.visit(this) || depth == DEPTH_ZERO)
			return;
		// get the info again because it might have been changed by the visitor
		info = getResourceInfo(includePhantoms, false);
		if (info == null)
			return;
		// thread safety: (cache the type to avoid changes -- we might not be inside an operation)
		int type = info.getType();
		if (type == FILE)
			return;
		// if we had a gender change we need to fix up the resource before asking for its members
		IContainer resource = getType() != type ? (IContainer) workspace.newResource(getFullPath(), type) : (IContainer) this;
		IResource[] members = resource.members(memberFlags);
		for (int i = 0; i < members.length; i++)
			members[i].accept(visitor, DEPTH_ZERO, memberFlags);
	}

	protected void assertCopyRequirements(IPath destination, int destinationType, int updateFlags) throws CoreException {
		IStatus status = checkCopyRequirements(destination, destinationType, updateFlags);
		if (!status.isOK()) {
			// this assert is ok because the error cases generated by the
			// check method above indicate assertion conditions.
			Assert.isTrue(false, status.getChildren()[0].getMessage());
		}
	}

	protected void assertLinkRequirements(IPath localLocation, int updateFlags) throws CoreException {
		checkDoesNotExist(getFlags(getResourceInfo(false, false)), true);
		boolean allowMissingLocal = (updateFlags & IResource.ALLOW_MISSING_LOCAL) != 0;
		IStatus locationStatus = workspace.validateLinkLocation(this, localLocation);
		//we only tolerate an undefined path variable in the allow missing local case
		if (locationStatus.getSeverity() == IStatus.ERROR || (locationStatus.getCode() == IResourceStatus.VARIABLE_NOT_DEFINED_WARNING && !allowMissingLocal))
			throw new ResourceException(locationStatus);
		//check that the parent exists and is open
		Container parent = (Container) getParent();
		parent.checkAccessible(getFlags(parent.getResourceInfo(false, false)));
		//check if the file exists
		java.io.File localFile = workspace.getPathVariableManager().resolvePath(localLocation).toFile();
		boolean localExists = localFile.exists();
		if (!allowMissingLocal && !localExists) {
			String msg = Messages.bind(Messages.links_localDoesNotExist, localFile);
			throw new ResourceException(IResourceStatus.NOT_FOUND_LOCAL, getFullPath(), msg, null);
		}
		//resource type and file system type must match
		if (localExists && ((getType() == IResource.FOLDER) != localFile.isDirectory())) {
			String msg = Messages.bind(Messages.links_wrongLocalType, getFullPath());
			throw new ResourceException(IResourceStatus.WRONG_TYPE_LOCAL, getFullPath(), msg, null);
		}
	}

	protected void assertMoveRequirements(IPath destination, int destinationType, int updateFlags) throws CoreException {
		IStatus status = checkMoveRequirements(destination, destinationType, updateFlags);
		if (!status.isOK()) {
			// this assert is ok because the error cases generated by the
			// check method above indicate assertion conditions.
			Assert.isTrue(false, status.getChildren()[0].getMessage());
		}
	}

	public void checkAccessible(int flags) throws CoreException {
		checkExists(flags, true);
	}

	/**
	 * This method reports errors in two different ways. It can throw a
	 * CoreException or return a status. CoreExceptions are used according to the
	 * specification of the copy method. Programming errors, that would usually be
	 * prevented by using an "Assert" code, are reported as an IStatus. We're doing
	 * this way because we have two different methods to copy resources:
	 * IResource#copy and IWorkspace#copy. The first one gets the error and throws
	 * its message in an AssertionFailureException. The second one just throws a
	 * CoreException using the status returned by this method.
	 * 
	 * @see IResource#copy(IPath, int, IProgressMonitor)
	 */
	public IStatus checkCopyRequirements(IPath destination, int destinationType, int updateFlags) throws CoreException {
		String message = Messages.resources_copyNotMet;
		MultiStatus status = new MultiStatus(ResourcesPlugin.PI_RESOURCES, IResourceStatus.INVALID_VALUE, message, null);
		if (destination == null) {
			message = Messages.resources_destNotNull;
			return new ResourceStatus(IResourceStatus.INVALID_VALUE, getFullPath(), message);
		}
		destination = makePathAbsolute(destination);
		if (getFullPath().isPrefixOf(destination)) {
			message = Messages.bind(Messages.resources_copyDestNotSub, getFullPath());
			status.add(new ResourceStatus(IResourceStatus.INVALID_VALUE, getFullPath(), message));
		}
		checkValidPath(destination, destinationType, false);

		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_INFINITE);

		Resource dest = workspace.newResource(destination, destinationType);
		dest.checkDoesNotExist();

		// ensure we aren't trying to copy a file to a project
		if (getType() == IResource.FILE && destinationType == IResource.PROJECT) {
			message = Messages.resources_fileToProj;
			throw new ResourceException(IResourceStatus.INVALID_VALUE, getFullPath(), message, null);
		}

		// ensure we aren't trying to copy a linked resource into a folder
		Container parent = (Container) dest.getParent();
		boolean isDeepCopy = (updateFlags & IResource.SHALLOW) == 0;
		if (isLinked() && !isDeepCopy && (parent == null || parent.getType() != IResource.PROJECT)) {
			message = Messages.bind(Messages.links_copyNotProject, getFullPath(), destination);
			throw new ResourceException(IResourceStatus.INVALID_VALUE, getFullPath(), message, null);
		}

		// we can't copy into a closed project
		if (destinationType != IResource.PROJECT) {
			Project project = (Project) dest.getProject();
			info = project.getResourceInfo(false, false);
			project.checkAccessible(getFlags(info));

			if (!parent.equals(project)) {
				info = parent.getResourceInfo(false, false);
				parent.checkExists(getFlags(info), true);
			}
		}
		if (isUnderLink() || dest.isUnderLink()) {
			//make sure location is not null.  This can occur with linked resources relative to
			//undefined path variables
			IPath sourceLocation = getLocation();
			if (sourceLocation == null) {
				message = Messages.bind(Messages.localstore_locationUndefined, getFullPath());
				throw new ResourceException(IResourceStatus.FAILED_READ_LOCAL, getFullPath(), message, null);
			}
			IPath destLocation = dest.getLocation();
			if (destLocation == null) {
				message = Messages.bind(Messages.localstore_locationUndefined, dest.getFullPath());
				throw new ResourceException(IResourceStatus.FAILED_READ_LOCAL, dest.getFullPath(), message, null);
			}
			//make sure location of source is not a prefix of the location of the destination
			//this can occur if the source or destination is a linked resource
			if (sourceLocation.isPrefixOf(destLocation)) {
				message = Messages.bind(Messages.resources_copyDestNotSub, getFullPath());
				throw new ResourceException(IResourceStatus.INVALID_VALUE, getFullPath(), message, null);
			}
		}

		return status.isOK() ? Status.OK_STATUS : (IStatus) status;
	}

	/**
	 * Helper method that considers case insensitive file systems.
	 */
	protected void checkDoesNotExist() throws CoreException {
		// should consider getting the ResourceInfo as a paramenter to reduce tree lookups

		//first check the tree for an exact case match
		checkDoesNotExist(getFlags(getResourceInfo(false, false)), false);
		if (CoreFileSystemLibrary.isCaseSensitive()) {
			return;
		}
		//now look for a matching case variant in the tree
		IResource variant = findExistingResourceVariant(getFullPath());
		if (variant == null)
			return;
		String msg = Messages.bind(Messages.resources_existsDifferentCase, variant.getFullPath());
		throw new ResourceException(IResourceStatus.CASE_VARIANT_EXISTS, variant.getFullPath(), msg, null);
	}

	/**
	 * Checks that this resource does not exist.  
	 *
	 * @exception CoreException if this resource exists
	 */
	public void checkDoesNotExist(int flags, boolean checkType) throws CoreException {
		// See if there is any resource at all.  If none then we are happy.
		if (!exists(flags, false))
			return;
		// We know there is something in the tree at this path.
		// If we are checking type then go ahead and check the type.
		// If there is nothing there of this resource's type, then return.
		if ((checkType && !exists(flags, checkType)))
			return;
		String message = Messages.bind(Messages.resources_mustNotExist, getFullPath());
		throw new ResourceException(checkType ? IResourceStatus.RESOURCE_EXISTS : IResourceStatus.PATH_OCCUPIED, getFullPath(), message, null);
	}

	/**
	 * Checks that this resource exists.
	 * If checkType is true, the type of this resource and the one in the tree must match.
	 *
	 * @exception CoreException if this resource does not exist
	 */
	public void checkExists(int flags, boolean checkType) throws CoreException {
		if (!exists(flags, checkType)) {
			String message = Messages.bind(Messages.resources_mustExist, getFullPath());
			throw new ResourceException(IResourceStatus.RESOURCE_NOT_FOUND, getFullPath(), message, null);
		}
	}

	/**
	 * Checks that this resource is local to the given depth.  
	 *
	 * @exception CoreException if this resource is not local
	 */
	public void checkLocal(int flags, int depth) throws CoreException {
		if (!isLocal(flags, depth)) {
			String message = Messages.bind(Messages.resources_mustBeLocal, getFullPath());
			throw new ResourceException(IResourceStatus.RESOURCE_NOT_LOCAL, getFullPath(), message, null);
		}
	}

	/**
	 * This method reports errors in two different ways. It can throw a
	 * CoreException or log a status. CoreExceptions are used according
	 * to the specification of the move method. Programming errors, that
	 * would usually be prevented by using an "Assert" code, are reported as
	 * an IStatus.
	 * We're doing this way because we have two different methods to move
	 * resources: IResource#move and IWorkspace#move. The first one gets
	 * the error and throws its message in an AssertionFailureException. The
	 * second one just throws a CoreException using the status returned
	 * by this method.
	 * 
	 * @see IResource#move(IPath, int, IProgressMonitor)
	 */
	protected IStatus checkMoveRequirements(IPath destination, int destinationType, int updateFlags) throws CoreException {
		String message = Messages.resources_moveNotMet;
		MultiStatus status = new MultiStatus(ResourcesPlugin.PI_RESOURCES, IResourceStatus.INVALID_VALUE, message, null);
		if (destination == null) {
			message = Messages.resources_destNotNull;
			return new ResourceStatus(IResourceStatus.INVALID_VALUE, getFullPath(), message);
		}
		destination = makePathAbsolute(destination);
		if (getFullPath().isPrefixOf(destination)) {
			message = Messages.bind(Messages.resources_moveDestNotSub, getFullPath());
			status.add(new ResourceStatus(IResourceStatus.INVALID_VALUE, getFullPath(), message));
		}
		checkValidPath(destination, destinationType, false);

		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_INFINITE);

		Resource dest = workspace.newResource(destination, destinationType);

		// check if we are only changing case
		IResource variant = CoreFileSystemLibrary.isCaseSensitive() ? null : findExistingResourceVariant(destination);
		if (variant == null || !this.equals(variant))
			dest.checkDoesNotExist();

		// ensure we aren't trying to move a file to a project
		if (getType() == IResource.FILE && dest.getType() == IResource.PROJECT) {
			message = Messages.resources_fileToProj;
			throw new ResourceException(new ResourceStatus(IResourceStatus.INVALID_VALUE, getFullPath(), message));
		}

		// ensure we aren't trying to shallow move a linked resource into a folder
		Container parent = (Container) dest.getParent();
		boolean isDeepMove = (updateFlags & IResource.SHALLOW) == 0;
		if (!isDeepMove && isLinked() && (parent == null || parent.getType() != IResource.PROJECT)) {
			message = Messages.bind(Messages.links_moveNotProject, getFullPath(), destination);
			throw new ResourceException(new ResourceStatus(IResourceStatus.INVALID_VALUE, getFullPath(), message));
		}

		// we can't move into a closed project
		if (destinationType != IResource.PROJECT) {
			Project project = (Project) dest.getProject();
			info = project.getResourceInfo(false, false);
			project.checkAccessible(getFlags(info));

			if (!parent.equals(project)) {
				info = parent.getResourceInfo(false, false);
				parent.checkExists(getFlags(info), true);
			}
		}
		if (isUnderLink() || dest.isUnderLink()) {
			//make sure location is not null.  This can occur with linked resources relative to
			//undefined path variables
			IPath sourceLocation = getLocation();
			if (sourceLocation == null) {
				message = Messages.bind(Messages.localstore_locationUndefined, getFullPath());
				throw new ResourceException(IResourceStatus.FAILED_READ_LOCAL, getFullPath(), message, null);
			}
			IPath destLocation = dest.getLocation();
			if (destLocation == null) {
				message = Messages.bind(Messages.localstore_locationUndefined, dest.getFullPath());
				throw new ResourceException(IResourceStatus.FAILED_READ_LOCAL, dest.getFullPath(), message, null);
			}
			//make sure location of source is not a prefix of the location of the destination
			//this can occur if the source or destination is a linked resource
			if (sourceLocation.isPrefixOf(destLocation)) {
				message = Messages.bind(Messages.resources_moveDestNotSub, getFullPath());
				throw new ResourceException(IResourceStatus.INVALID_VALUE, getFullPath(), message, null);
			}
		}

		return status.isOK() ? Status.OK_STATUS : (IStatus) status;
	}

	/**
	 * Checks that the supplied path is valid according to Workspace.validatePath().
	 *
	 * @exception CoreException if the path is not valid
	 */
	public void checkValidPath(IPath toValidate, int type, boolean lastSegmentOnly) throws CoreException {
		IStatus result = workspace.validatePath(toValidate, type, lastSegmentOnly);
		if (!result.isOK())
			throw new ResourceException(result);
	}

	/* (non-Javadoc)
	 * @see IResource#clearHistory(IProgressMonitor)
	 */
	public void clearHistory(IProgressMonitor monitor) {
		getLocalManager().getHistoryStore().remove(getFullPath(), monitor);
	}

	/*
	 *  (non-Javadoc)
	 * @see ISchedulingRule#contains(ISchedulingRule)
	 */
	public boolean contains(ISchedulingRule rule) {
		if (this == rule)
			return true;
		//must allow notifications to nest in all resource rules
		if (rule.getClass().equals(WorkManager.NotifyRule.class))
			return true;
		if (rule instanceof MultiRule) {
			MultiRule multi = (MultiRule) rule;
			ISchedulingRule[] children = multi.getChildren();
			for (int i = 0; i < children.length; i++)
				if (!contains(children[i]))
					return false;
			return true;
		}
		if (!(rule instanceof IResource))
			return false;
		return path.isPrefixOf(((IResource) rule).getFullPath());
	}

	public void convertToPhantom() throws CoreException {
		ResourceInfo info = getResourceInfo(false, true);
		if (info == null || isPhantom(getFlags(info)))
			return;
		info.clearSessionProperties();
		info.set(M_PHANTOM);
		getLocalManager().updateLocalSync(info, I_NULL_SYNC_INFO);
		info.clearModificationStamp();
		// should already be done by the #deleteResource call but left in 
		// just to be safe and for code clarity.
		info.setMarkers(null);
	}

	/* (non-Javadoc)
	 * Used when a folder is to be copied to a project.
	 * @see IResource#copy(IProjectDescription, int, IProgressMonitor)
	 */
	public void copy(IProjectDescription destDesc, int updateFlags, IProgressMonitor monitor) throws CoreException {
		Assert.isNotNull(destDesc);
		monitor = Policy.monitorFor(monitor);
		try {
			String message = Messages.bind(Messages.resources_copying, getFullPath());
			monitor.beginTask(message, Policy.totalWork);
			try {
				workspace.prepareOperation(workspace.getRoot(), monitor);
				// The following assert method throws CoreExceptions as stated in the IResource.copy API
				// and assert for programming errors. See checkCopyRequirements for more information.
				IPath destPath = new Path(destDesc.getName()).makeAbsolute();
				assertCopyRequirements(destPath, getType(), updateFlags);
				Project destProject = (Project) workspace.getRoot().getProject(destPath.lastSegment());
				workspace.beginOperation(true);

				// create and open the new project
				destProject.create(destDesc, Policy.subMonitorFor(monitor, Policy.opWork * 5 / 100));
				destProject.open(Policy.subMonitorFor(monitor, Policy.opWork * 5 / 100));

				// copy the children
				// FIXME: fix the progress monitor here...create a sub monitor and do a worked(1) after each child instead
				IResource[] children = ((IContainer) this).members(IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS);
				for (int i = 0; i < children.length; i++) {
					Resource child = (Resource) children[i];
					child.copy(destPath.append(child.getName()), updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 60 / 100 / children.length));
				}

				// copy over the properties
				getPropertyManager().copy(this, destProject, DEPTH_ZERO);
				monitor.worked(Policy.opWork * 15 / 100);

			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(workspace.getRoot(), true, Policy.subMonitorFor(monitor, Policy.endOpWork));
			}
		} finally {
			monitor.done();
		}
	}

	/* (non-Javadoc)
	 * @see IResource#copy(IProjectDescription, boolean, IProgressMonitor)
	 */
	public void copy(IProjectDescription destDesc, boolean force, IProgressMonitor monitor) throws CoreException {
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		copy(destDesc, updateFlags, monitor);
	}

	/* (non-Javadoc)
	 * @see IResource#copy(IPath, int, IProgressMonitor)
	 */
	public void copy(IPath destination, int updateFlags, IProgressMonitor monitor) throws CoreException {
		try {
			monitor = Policy.monitorFor(monitor);
			String message = Messages.bind(Messages.resources_copying, getFullPath());
			monitor.beginTask(message, Policy.totalWork);
			Policy.checkCanceled(monitor);
			destination = makePathAbsolute(destination);
			checkValidPath(destination, getType(), false);
			Resource destResource = workspace.newResource(destination, getType());
			final ISchedulingRule rule = workspace.getRuleFactory().copyRule(this, destResource);
			try {
				workspace.prepareOperation(rule, monitor);
				// The following assert method throws CoreExceptions as stated in the IResource.copy API
				// and assert for programming errors. See checkCopyRequirements for more information.
				assertCopyRequirements(destination, getType(), updateFlags);
				workspace.beginOperation(true);
				getLocalManager().copy(this, destResource, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork));
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true, Policy.subMonitorFor(monitor, Policy.endOpWork));
			}
		} finally {
			monitor.done();
		}
	}

	/* (non-Javadoc)
	 * @see IResource#copy(IPath, boolean, IProgressMonitor)
	 */
	public void copy(IPath destination, boolean force, IProgressMonitor monitor) throws CoreException {
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		copy(destination, updateFlags, monitor);
	}

	/**
	 * Count the number of resources in the tree from this container to the
	 * specified depth. Include this resource. Include phantoms if
	 * the phantom boolean is true.
	 */
	public int countResources(int depth, boolean phantom) {
		return workspace.countResources(path, depth, phantom);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.core.resources.IFolder#createLink(IPath, int, IProgressMonitor)
	 * @see org.eclipse.core.resources.IFile#createLink(IPath, int, IProgressMonitor)
	 */
	public void createLink(IPath localLocation, int updateFlags, IProgressMonitor monitor) throws CoreException {
		monitor = Policy.monitorFor(monitor);
		try {
			String message = Messages.bind(Messages.links_creating, getFullPath());
			monitor.beginTask(message, Policy.totalWork);
			Policy.checkCanceled(monitor);
			checkValidPath(path, FOLDER, true);
			final ISchedulingRule rule = workspace.getRuleFactory().createRule(this);
			try {
				workspace.prepareOperation(rule, monitor);
				//if the location doesn't have a device, see if the OS will assign one
				if (localLocation.isAbsolute() && localLocation.getDevice() == null)
					localLocation = new Path(localLocation.toFile().getAbsolutePath());
				assertLinkRequirements(localLocation, updateFlags);
				workspace.broadcastEvent(LifecycleEvent.newEvent(LifecycleEvent.PRE_LINK_CREATE, this));
				workspace.beginOperation(true);
				// resolve any variables used in the location path
				IPath resolvedLocation = workspace.getPathVariableManager().resolvePath(localLocation);
				ResourceInfo info = workspace.createResource(this, false);
				info.set(M_LINK);
				getLocalManager().link(this, resolvedLocation);
				monitor.worked(Policy.opWork * 5 / 100);
				//save the location in the project description
				Project project = (Project) getProject();
				project.internalGetDescription().setLinkLocation(getName(), new LinkDescription(this, localLocation));
				project.writeDescription(IResource.NONE);
				monitor.worked(Policy.opWork * 5 / 100);

				//refresh to discover any new resources below this linked location
				if (getType() != IResource.FILE)
					refreshLocal(DEPTH_INFINITE, Policy.subMonitorFor(monitor, Policy.opWork * 90 / 100));
				else
					monitor.worked(Policy.opWork * 90 / 100);
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true, Policy.subMonitorFor(monitor, Policy.endOpWork));
			}
		} finally {
			monitor.done();
		}
	}

	/* (non-Javadoc)
	 * @see IResource#createMarker(String)
	 */
	public IMarker createMarker(String type) throws CoreException {
		Assert.isNotNull(type);
		final ISchedulingRule rule = workspace.getRuleFactory().markerRule(this);
		try {
			workspace.prepareOperation(rule, null);
			checkAccessible(getFlags(getResourceInfo(false, false)));
			workspace.beginOperation(true);
			MarkerInfo info = new MarkerInfo();
			info.setType(type);
			info.setCreationTime(System.currentTimeMillis());
			workspace.getMarkerManager().add(this, info);
			return new Marker(this, info.getId());
		} finally {
			workspace.endOperation(rule, false, null);
		}
	}

	/* (non-Javadoc)
	 * @see IResource#delete(boolean, IProgressMonitor)
	 */
	public void delete(boolean force, IProgressMonitor monitor) throws CoreException {
		delete(force ? IResource.FORCE : IResource.NONE, monitor);
	}

	/* (non-Javadoc)
	 * @see IResource#delete(int, IProgressMonitor)
	 */
	public void delete(int updateFlags, IProgressMonitor monitor) throws CoreException {
		monitor = Policy.monitorFor(monitor);
		try {
			String message = Messages.bind(Messages.resources_deleting, getFullPath());
			monitor.beginTask(message, Policy.totalWork * 1000);
			final ISchedulingRule rule = workspace.getRuleFactory().deleteRule(this);
			try {
				workspace.prepareOperation(rule, monitor);
				// if there is no resource then there is nothing to delete so just return
				if (!exists())
					return;
				workspace.beginOperation(true);
				IPath originalLocation = getLocation();
				boolean wasLinked = isLinked();
				message = Messages.resources_deleteProblem;
				MultiStatus status = new MultiStatus(ResourcesPlugin.PI_RESOURCES, IStatus.ERROR, message, null);
				WorkManager workManager = workspace.getWorkManager();
				ResourceTree tree = new ResourceTree(workManager.getLock(), status, updateFlags);
				int depth = 0;
				try {
					depth = workManager.beginUnprotected();
					unprotectedDelete(tree, updateFlags, monitor);
				} finally {
					workManager.endUnprotected(depth);
				}
				if (getType() == ROOT) {
					// need to clear out the root info
					workspace.getMarkerManager().removeMarkers(this, IResource.DEPTH_ZERO);
					getPropertyManager().deleteProperties(this, IResource.DEPTH_ZERO);
					getResourceInfo(false, false).clearSessionProperties();
				}
				// Invalidate the tree for further use by clients.
				tree.makeInvalid();
				if (!tree.getStatus().isOK())
					throw new ResourceException(tree.getStatus());
				//update any aliases of this resource
				//note that deletion of a linked resource cannot affect other resources
				if (!wasLinked)
					workspace.getAliasManager().updateAliases(this, originalLocation, IResource.DEPTH_INFINITE, monitor);
				//make sure the rule factory is cleared on project deletion
				if (getType() == PROJECT)
					((Rules) workspace.getRuleFactory()).setRuleFactory((IProject) this, null);
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true, Policy.subMonitorFor(monitor, Policy.endOpWork * 1000));
			}
		} finally {
			monitor.done();
		}
	}

	/* (non-Javadoc)
	 * @see IProject#delete(boolean, boolean, IProgressMonitor)
	 * @see IWorkspaceRoot#delete(boolean, boolean, IProgressMonitor)
	 * N.B. This is not an IResource method!
	 */
	public void delete(boolean force, boolean keepHistory, IProgressMonitor monitor) throws CoreException {
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		updateFlags |= keepHistory ? IResource.KEEP_HISTORY : IResource.NONE;
		delete(updateFlags, monitor);
	}

	/* (non-Javadoc)
	 * @see IResource#deleteMarkers(String, boolean, int)
	 */
	public void deleteMarkers(String type, boolean includeSubtypes, int depth) throws CoreException {
		final ISchedulingRule rule = workspace.getRuleFactory().markerRule(this);
		try {
			workspace.prepareOperation(rule, null);
			ResourceInfo info = getResourceInfo(false, false);
			checkAccessible(getFlags(info));

			workspace.beginOperation(true);
			workspace.getMarkerManager().removeMarkers(this, type, includeSubtypes, depth);
		} finally {
			workspace.endOperation(rule, false, null);
		}
	}

	/**
	 * This method should be called to delete a resource from the tree because it will also
	 * delete its properties and markers.  If a status object is provided, minor exceptions are
	 * added, otherwise they are thrown.  If major exceptions occur, they are always thrown.
	 */
	public void deleteResource(boolean convertToPhantom, MultiStatus status) throws CoreException {
		// delete properties
		CoreException err = null;
		try {
			getPropertyManager().deleteResource(this);
		} catch (CoreException e) {
			if (status != null)
				status.add(e.getStatus());
			else
				err = e;
		}
		// remove markers on this resource and its descendents
		if (exists())
			getMarkerManager().removeMarkers(this, IResource.DEPTH_INFINITE);
		// if this is a linked resource, remove the entry from the project description
		if (isLinked()) {
			//pre-delete notification to internal infrastructure
			workspace.broadcastEvent(LifecycleEvent.newEvent(LifecycleEvent.PRE_LINK_DELETE, this));
			Project project = (Project) getProject();
			ProjectDescription description = project.internalGetDescription();
			description.setLinkLocation(getName(), null);
			project.internalSetDescription(description, true);
			project.writeDescription(IResource.FORCE);
		}
		
		// check if we deleted a preferences file 
		ProjectPreferences.deleted(this);

		/* if we are synchronizing, do not delete the resource. Convert it
		 into a phantom. Actual deletion will happen when we refresh or push. */
		if (convertToPhantom && getType() != PROJECT && synchronizing(getResourceInfo(true, false)))
			convertToPhantom();
		else
			workspace.deleteResource(this);
		if (err != null)
			throw err;
	}

	/* (non-Javadoc)
	 * @see IResource#equals(Object)
	 */
	public boolean equals(Object target) {
		if (this == target)
			return true;
		if (!(target instanceof Resource))
			return false;
		Resource resource = (Resource) target;
		return getType() == resource.getType() && path.equals(resource.path) && workspace.equals(resource.workspace);
	}

	/* (non-Javadoc)
	 * @see IResource#exists()
	 */
	public boolean exists() {
		ResourceInfo info = getResourceInfo(false, false);
		return exists(getFlags(info), true);
	}

	public boolean exists(int flags, boolean checkType) {
		return flags != NULL_FLAG && !(checkType && ResourceInfo.getType(flags) != getType());
	}

	/* (non-Javadoc)
	 * @see IResource#findMarker(long)
	 */
	public IMarker findMarker(long id) {
		return workspace.getMarkerManager().findMarker(this, id);
	}

	/* (non-Javadoc)
	 * @see IResource#findMarkers(String, boolean, int)
	 */
	public IMarker[] findMarkers(String type, boolean includeSubtypes, int depth) throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		checkAccessible(getFlags(info));
		// It might happen that from this point the resource is not accessible anymore.
		// But markers have the #exists method that callers can use to check if it is
		// still valid.
		return workspace.getMarkerManager().findMarkers(this, type, includeSubtypes, depth);
	}

	protected void fixupAfterMoveSource() throws CoreException {
		ResourceInfo info = getResourceInfo(true, true);
		//if a linked resource is moved, we need to remove the location info from the .project 
		if (isLinked()) {
			Project project = (Project) getProject();
			project.internalGetDescription().setLinkLocation(getName(), null);
			project.writeDescription(IResource.NONE);
		}

		// check if we deleted a preferences file 
		ProjectPreferences.deleted(this);		
		
		if (!synchronizing(info)) {
			workspace.deleteResource(this);
			return;
		}
		info.clearSessionProperties();
		info.clear(M_LOCAL_EXISTS);
		info.setLocalSyncInfo(I_NULL_SYNC_INFO);
		info.set(M_PHANTOM);
		info.clearModificationStamp();
		info.setMarkers(null);
	}

	/* (non-Javadoc)
	 * @see IResource#getFileExtension()
	 */
	public String getFileExtension() {
		String name = getName();
		int index = name.lastIndexOf('.');
		if (index == -1)
			return null;
		if (index == (name.length() - 1))
			return ""; //$NON-NLS-1$
		return name.substring(index + 1);
	}

	public int getFlags(ResourceInfo info) {
		return (info == null) ? NULL_FLAG : info.getFlags();
	}

	/* (non-Javadoc)
	 * @see IResource#getFullPath()
	 */
	public IPath getFullPath() {
		return path;
	}

	public FileSystemResourceManager getLocalManager() {
		return workspace.getFileSystemManager();
	}

	/* (non-Javadoc)
	 * @see IResource#getLocalTimeStamp()
	 */
	public long getLocalTimeStamp() {
		ResourceInfo info = getResourceInfo(false, false);
		return info == null ? IResource.NULL_STAMP : info.getLocalSyncInfo();
	}

	/* (non-Javadoc)
	 * @see IResource#getLocation()
	 */
	public IPath getLocation() {
		IProject project = getProject();
		if (project != null && !project.exists())
			return null;
		return getLocalManager().locationFor(this);
	}

	public URL getLocationURL() {
		IProject project = getProject();
		if (project != null && !project.exists())
			return null;
		try {
			return new URL("platform:/resource" + getFullPath()); //$NON-NLS-1$
		} catch (MalformedURLException e) {
			return null;
		}
	}

	/* (non-Javadoc)
	 * @see IResource#getMarker(long)
	 */
	public IMarker getMarker(long id) {
		return new Marker(this, id);
	}

	protected MarkerManager getMarkerManager() {
		return workspace.getMarkerManager();
	}

	/* (non-Javadoc)
	 * @see IResource#getModificationStamp()
	 */
	public long getModificationStamp() {
		ResourceInfo info = getResourceInfo(false, false);
		return info == null ? IResource.NULL_STAMP : info.getModificationStamp();
	}

	/* (non-Javadoc)
	 * @see IResource#getName()
	 */
	public String getName() {
		return path.lastSegment();
	}

	/* (non-Javadoc)
	 * @see IResource#getParent()
	 */
	public IContainer getParent() {
		int segments = path.segmentCount();
		//zero and one segments handled by subclasses
		Assert.isLegal(segments > 1, path.toString());
		if (segments == 2)
			return workspace.getRoot().getProject(path.segment(0));
		return (IFolder) workspace.newResource(path.removeLastSegments(1), IResource.FOLDER);
	}

	/* (non-Javadoc)
	 * @see IResource#getPersistentProperty(QualifiedName)
	 */
	public String getPersistentProperty(QualifiedName key) throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		return getPropertyManager().getProperty(this, key);
	}

	/* (non-Javadoc)
	 * @see IResource#getProject()
	 */
	public IProject getProject() {
		return workspace.getRoot().getProject(path.segment(0));
	}

	/* (non-Javadoc)
	 * @see IResource#getProjectRelativePath()
	 */
	public IPath getProjectRelativePath() {
		return getFullPath().removeFirstSegments(ICoreConstants.PROJECT_SEGMENT_LENGTH);
	}

	public PropertyManager getPropertyManager() {
		return workspace.getPropertyManager();
	}

	/* (non-Javadoc)
	 * @see IResource#getRawLocation()
	 */
	public IPath getRawLocation() {
		if (isLinked())
			return ((Project) getProject()).internalGetDescription().getLinkLocation(getName());
		return getLocation();
	}

	/* (non-Javadoc)
	 * @see IResource#getResourceAttributes()
	 */
	public ResourceAttributes getResourceAttributes() {
		// get the attributes
		IPath location = getLocation();
		if (location == null)
			return null;
		return CoreFileSystemLibrary.getResourceAttributes(location.toOSString());
	}

	/**
	 * Returns the resource info.  Returns null if the resource doesn't exist.
	 * If the phantom flag is true, phantom resources are considered.
	 * If the mutable flag is true, a mutable info is returned.
	 */
	public ResourceInfo getResourceInfo(boolean phantom, boolean mutable) {
		return workspace.getResourceInfo(getFullPath(), phantom, mutable);
	}

	/* (non-Javadoc)
	 * @see IResource#getSessionProperty(QualifiedName)
	 */
	public Object getSessionProperty(QualifiedName key) throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		return info.getSessionProperty(key);
	}

	/* (non-Javadoc)
	 * @see IResource#getType()
	 */
	public abstract int getType();

	public String getTypeString() {
		switch (getType()) {
			case FILE :
				return "L"; //$NON-NLS-1$
			case FOLDER :
				return "F"; //$NON-NLS-1$
			case PROJECT :
				return "P"; //$NON-NLS-1$
			case ROOT :
				return "R"; //$NON-NLS-1$
		}
		return ""; //$NON-NLS-1$
	}

	/* (non-Javadoc)
	 * @see IResource#getWorkspace()
	 */
	public IWorkspace getWorkspace() {
		return workspace;
	}

	public int hashCode() {
		// the container may be null if the identified resource 
		// does not exist so don't bother with it in the hash
		return getFullPath().hashCode();
	}

	/**
	 * Sets the M_LOCAL_EXISTS flag. Is internal so we don't have
	 * to begin an operation.
	 */
	protected void internalSetLocal(boolean flag, int depth) throws CoreException {
		ResourceInfo info = getResourceInfo(true, true);
		//only make the change if it's not already in desired state
		if (info.isSet(M_LOCAL_EXISTS) != flag) {
			if (flag && !isPhantom(getFlags(info))) {
				info.set(M_LOCAL_EXISTS);
				workspace.updateModificationStamp(info);
			} else {
				info.clear(M_LOCAL_EXISTS);
				info.clearModificationStamp();
			}
		}
		if (getType() == IResource.FILE || depth == IResource.DEPTH_ZERO)
			return;
		if (depth == IResource.DEPTH_ONE)
			depth = IResource.DEPTH_ZERO;
		IResource[] children = ((IContainer) this).members();
		for (int i = 0; i < children.length; i++)
			((Resource) children[i]).internalSetLocal(flag, depth);
	}

	/* (non-Javadoc)
	 * @see IResource#isAccessible()
	 */
	public boolean isAccessible() {
		return exists();
	}

	/* (non-Javadoc)
	 * @see ISchedulingRule#isConflicting(ISchedulingRule)
	 */
	public boolean isConflicting(ISchedulingRule rule) {
		//must not schedule at same time as notification
		if (rule.getClass().equals(WorkManager.NotifyRule.class))
			return true;
		if (!(rule instanceof IResource))
			return false;
		IPath otherPath = ((IResource) rule).getFullPath();
		return path.isPrefixOf(otherPath) || otherPath.isPrefixOf(path);
	}

	/* (non-Javadoc)
	 * @see IResource#isLocal(int)
	 */
	public boolean isLocal(int depth) {
		ResourceInfo info = getResourceInfo(false, false);
		return isLocal(getFlags(info), depth);
	}

	/**
	 * Note the depth parameter is intentionally ignored because 
	 * this method is over-ridden by Container.isLocal().
	 */
	public boolean isLocal(int flags, int depth) {
		if (getType() == PROJECT)
			return flags != NULL_FLAG; // exists
		return flags != NULL_FLAG && ResourceInfo.isSet(flags, M_LOCAL_EXISTS);
	}

	/* (non-Javadoc)
	 * @see IResource#isPhantom()
	 */
	public boolean isPhantom() {
		ResourceInfo info = getResourceInfo(true, false);
		return isPhantom(getFlags(info));
	}

	public boolean isPhantom(int flags) {
		return flags != NULL_FLAG && ResourceInfo.isSet(flags, M_PHANTOM);
	}

	/** (non-Javadoc)
	 * @see IResource#isReadOnly()
	 * @deprecated
	 */
	public boolean isReadOnly() {
		IPath location = getLocation();
		if (location == null)
			return false;
		return CoreFileSystemLibrary.isReadOnly(location.toOSString());
	}

	/* (non-Javadoc)
	 * @see IResource#isSynchronized(int)
	 */
	public boolean isSynchronized(int depth) {
		return getLocalManager().isSynchronized(this, depth);
	}

	protected IPath makePathAbsolute(IPath target) {
		if (target.isAbsolute())
			return target;
		return getParent().getFullPath().append(target);
	}

	/* (non-Javadoc)
	 * @see IResource#move(IProjectDescription, boolean, IProgressMonitor)
	 */
	public void move(IProjectDescription description, boolean force, boolean keepHistory, IProgressMonitor monitor) throws CoreException {
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		updateFlags |= keepHistory ? IResource.KEEP_HISTORY : IResource.NONE;
		move(description, updateFlags, monitor);
	}

	/* (non-Javadoc)
	 * @see IResource#move(IPath, int, IProgressMonitor)
	 */
	public void move(IProjectDescription description, int updateFlags, IProgressMonitor monitor) throws CoreException {
		Assert.isNotNull(description);
		if (getType() != IResource.PROJECT) {
			String message = Messages.bind(Messages.resources_moveNotProject, getFullPath(), description.getName());
			throw new ResourceException(IResourceStatus.INVALID_VALUE, getFullPath(), message, null);
		}
		((Project) this).move(description, updateFlags, monitor);
	}

	/* (non-Javadoc)
	 * @see IResource#move(IPath, boolean, IProgressMonitor)
	 */
	public void move(IPath destination, boolean force, IProgressMonitor monitor) throws CoreException {
		move(destination, force ? IResource.FORCE : IResource.NONE, monitor);
	}

	/* (non-Javadoc)
	 * @see IResource#move(IPath, boolean, IProgressMonitor)
	 */
	public void move(IPath destination, boolean force, boolean keepHistory, IProgressMonitor monitor) throws CoreException {
		int updateFlags = force ? IResource.FORCE : IResource.NONE;
		updateFlags |= keepHistory ? IResource.KEEP_HISTORY : IResource.NONE;
		move(destination, updateFlags, monitor);
	}

	/* (non-Javadoc)
	 * @see IResource#move(IPath, int, IProgressMonitor)
	 */
	public void move(IPath destination, int updateFlags, IProgressMonitor monitor) throws CoreException {
		monitor = Policy.monitorFor(monitor);
		try {
			String message = Messages.bind(Messages.resources_moving, getFullPath());
			monitor.beginTask(message, Policy.totalWork);
			Policy.checkCanceled(monitor);
			destination = makePathAbsolute(destination);
			checkValidPath(destination, getType(), false);
			Resource destResource = workspace.newResource(destination, getType());
			final ISchedulingRule rule = workspace.getRuleFactory().moveRule(this, destResource);
			try {
				workspace.prepareOperation(rule, monitor);
				// The following assert method throws CoreExceptions as stated in the IResource.move API
				// and assert for programming errors. See checkMoveRequirements for more information.
				assertMoveRequirements(destination, getType(), updateFlags);
				workspace.beginOperation(true);
				IPath originalLocation = getLocation();
				message = Messages.resources_moveProblem;
				MultiStatus status = new MultiStatus(ResourcesPlugin.PI_RESOURCES, IStatus.ERROR, message, null);
				WorkManager workManager = workspace.getWorkManager();
				ResourceTree tree = new ResourceTree(workManager.getLock(), status, updateFlags);
				boolean success = false;
				int depth = 0;
				try {
					depth = workManager.beginUnprotected();
					success = unprotectedMove(tree, destResource, updateFlags, monitor);
				} finally {
					workManager.endUnprotected(depth);
				}
				// Invalidate the tree for further use by clients.
				tree.makeInvalid();
				//update any aliases of this resource and the destination
				if (success) {
					workspace.getAliasManager().updateAliases(this, originalLocation, IResource.DEPTH_INFINITE, monitor);
					workspace.getAliasManager().updateAliases(destResource, destResource.getLocation(), IResource.DEPTH_INFINITE, monitor);
				}
				if (!tree.getStatus().isOK())
					throw new ResourceException(tree.getStatus());
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true, Policy.subMonitorFor(monitor, Policy.endOpWork));
			}
		} finally {
			monitor.done();
		}
	}

	/* (non-Javadoc)
	 * @see IResource#refreshLocal(int, IProgressMonitor)
	 */
	public void refreshLocal(int depth, IProgressMonitor monitor) throws CoreException {
		monitor = Policy.monitorFor(monitor);
		try {
			boolean isRoot = getType() == ROOT;
			String message = isRoot ? Messages.resources_refreshingRoot : Messages.bind(Messages.resources_refreshing, getFullPath());
			monitor.beginTask("", Policy.totalWork); //$NON-NLS-1$
			monitor.subTask(message);
			boolean build = false;
			final ISchedulingRule rule = workspace.getRuleFactory().refreshRule(this);
			try {
				workspace.prepareOperation(rule, monitor);
				if (!isRoot && !getProject().isAccessible())
					return;
				workspace.beginOperation(true);
				build = getLocalManager().refresh(this, depth, true, Policy.subMonitorFor(monitor, Policy.opWork));
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, build, Policy.subMonitorFor(monitor, Policy.endOpWork));
			}
		} finally {
			monitor.done();
		}
	}

	/* (non-Javadoc)
	 * @see IResource#setLocal(boolean, int, IProgressMonitor)
	 */
	public void setLocal(boolean flag, int depth, IProgressMonitor monitor) throws CoreException {
		monitor = Policy.monitorFor(monitor);
		try {
			String message = Messages.resources_setLocal;
			monitor.beginTask(message, Policy.totalWork);
			try {
				workspace.prepareOperation(null, monitor);
				workspace.beginOperation(true);
				internalSetLocal(flag, depth);
				monitor.worked(Policy.opWork);
			} finally {
				workspace.endOperation(null, true, Policy.subMonitorFor(monitor, Policy.endOpWork));
			}
		} finally {
			monitor.done();
		}
	}

	/* (non-Javadoc)
	 * @see IResource#setLocalTimeStamp(long)
	 */
	public long setLocalTimeStamp(long value) throws CoreException {
		if (value < 0)
			throw new IllegalArgumentException("Illegal time stamp: " + value); //$NON-NLS-1$
		// fetch the info but don't bother making it mutable even though we are going
		// to modify it. It really doesn't matter as the change we are doing does not show up in deltas.
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		return getLocalManager().setLocalTimeStamp(this, info, value);
	}

	/* (non-Javadoc)
	 * @see IResource#setPersistentProperty(QualifiedName, String)
	 */
	public void setPersistentProperty(QualifiedName key, String value) throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		getPropertyManager().setProperty(this, key, value);
	}

	/** (non-Javadoc)
	 * @see IResource#setReadOnly(boolean)
	 * @deprecated
	 */
	public void setReadOnly(boolean readonly) {
		IPath location = getLocation();
		if (location != null)
			CoreFileSystemLibrary.setReadOnly(location.toOSString(), readonly);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.core.resources.IResource#setResourceAttributes(org.eclipse.core.resources.IResourceAttributes)
	 */
	public void setResourceAttributes(ResourceAttributes attributes) throws CoreException {
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		IPath location = getLocation();
		if (location == null) {
			String message = Messages.bind(Messages.localstore_locationUndefined, getFullPath());
			throw new ResourceException(IResourceStatus.FAILED_WRITE_LOCAL, getFullPath(), message, null);
		}
		CoreFileSystemLibrary.setResourceAttributes(location.toOSString(), attributes);
	}
	
	/* (non-Javadoc)
	 * @see IResource#setSessionProperty(QualifiedName, Object)
	 */
	public void setSessionProperty(QualifiedName key, Object value) throws CoreException {
		// fetch the info but don't bother making it mutable even though we are going
		// to modify it.  We don't know whether or not the tree is open and it really doesn't
		// matter as the change we are doing does not show up in deltas.
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		checkLocal(flags, DEPTH_ZERO);
		info.setSessionProperty(key, value);
	}

	/**
	 * Returns true if this resource has the potential to be
	 * (or have been) synchronized.  
	 */
	public boolean synchronizing(ResourceInfo info) {
		return info != null && info.getSyncInfo(false) != null;
	}

	/* (non-Javadoc)
	 * @see Object#toString()
	 */
	public String toString() {
		return getTypeString() + getFullPath().toString();
	}

	/* (non-Javadoc)
	 * @see IResource#touch(IProgressMonitor)
	 */
	public void touch(IProgressMonitor monitor) throws CoreException {
		monitor = Policy.monitorFor(monitor);
		try {
			String message = Messages.bind(Messages.resources_touch, getFullPath());
			monitor.beginTask(message, Policy.totalWork);
			final ISchedulingRule rule = workspace.getRuleFactory().modifyRule(this);
			try {
				workspace.prepareOperation(rule, monitor);
				ResourceInfo info = getResourceInfo(false, false);
				int flags = getFlags(info);
				checkAccessible(flags);
				checkLocal(flags, DEPTH_ZERO);

				workspace.beginOperation(true);
				// fake a change by incrementing the content ID
				info = getResourceInfo(false, true);
				info.incrementContentId();
				// forget content-related caching flags
				info.clear(M_CONTENT_CACHE);
				workspace.updateModificationStamp(info);
				monitor.worked(Policy.opWork);
			} catch (OperationCanceledException e) {
				workspace.getWorkManager().operationCanceled();
				throw e;
			} finally {
				workspace.endOperation(rule, true, Policy.subMonitorFor(monitor, Policy.endOpWork));
			}
		} finally {
			monitor.done();
		}
	}

	/**
	 * Helper method for case insensitive file systems.  Returns
	 * an existing resource whose path differs only in case from
	 * the given path, or null if no such resource exists.
	 */
	public IResource findExistingResourceVariant(IPath target) {
		if (!workspace.tree.includesIgnoreCase(target))
			return null;
		IPath result = Path.ROOT;
		int segmentCount = target.segmentCount();
		for (int i = 0; i < segmentCount; i++) {
			String[] childNames = workspace.tree.getNamesOfChildren(result);
			String name = findVariant(target.segment(i), childNames);
			if (name == null)
				return null;
			result = result.append(name);
		}
		return workspace.getRoot().findMember(result);
	}

	/**
	 * Searches for a variant of the given target in the list,
	 * that differs only in case. Returns the variant from
	 * the list if one is found, otherwise returns null.
	 */
	private String findVariant(String target, String[] list) {
		for (int i = 0; i < list.length; i++) {
			if (target.equalsIgnoreCase(list[i]))
				return list[i];
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see IResource#isDerived()
	 */
	public boolean isDerived() {
		ResourceInfo info = getResourceInfo(false, false);
		return isDerived(getFlags(info));
	}

	/**
	 * Returns whether the derived flag is set in the given resource info flags.
	 * 
	 * @param flags resource info flags (bitwuise or of M_* constants)
	 * @return <code>true</code> if the derived flag is set, and <code>false</code>
	 *    if the derived flag is not set or if the flags are <code>NULL_FLAG</code>
	 */
	public boolean isDerived(int flags) {
		return flags != NULL_FLAG && ResourceInfo.isSet(flags, ICoreConstants.M_DERIVED);
	}

	/* (non-Javadoc)
	 * @see IResource#isLinked()
	 */
	public boolean isLinked() {
		//only resources at depth two can be linked
		if (path.segmentCount() != 2)
			return false;
		ResourceInfo info = getResourceInfo(false, false);
		return info != null && info.isSet(M_LINK);
	}

	/* (non-Javadoc)
	 * @see IResource#setDerived(boolean)
	 */
	public void setDerived(boolean isDerived) throws CoreException {
		// fetch the info but don't bother making it mutable even though we are going
		// to modify it.  We don't know whether or not the tree is open and it really doesn't
		// matter as the change we are doing does not show up in deltas.
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		// ignore attempts to set derived flag on anything except files and folders
		if (info.getType() == FILE || info.getType() == FOLDER) {
			if (isDerived) {
				info.set(ICoreConstants.M_DERIVED);
			} else {
				info.clear(ICoreConstants.M_DERIVED);
			}
		}
	}

	/* (non-Javadoc)
	 * @see IResource#isTeamPrivateMember()
	 */
	public boolean isTeamPrivateMember() {
		ResourceInfo info = getResourceInfo(false, false);
		return isTeamPrivateMember(getFlags(info));
	}

	/**
	 * Returns whether the team private member flag is set in the given resource info flags.
	 * 
	 * @param flags resource info flags (bitwise or of M_* constants)
	 * @return <code>true</code> if the team private member flag is set, and 
	 *    <code>false</code> if the flag is not set or if the flags are <code>NULL_FLAG</code>
	 */
	public boolean isTeamPrivateMember(int flags) {
		return flags != NULL_FLAG && ResourceInfo.isSet(flags, ICoreConstants.M_TEAM_PRIVATE_MEMBER);
	}

	/**
	 * Returns true if this resource is a linked resource, or a child of a linked
	 * resource, and false otherwise.
	 */
	public boolean isUnderLink() {
		int depth = path.segmentCount();
		if (depth < 2)
			return false;
		if (depth == 2)
			return isLinked();
		//check if parent at depth two is a link
		IPath linkParent = path.removeLastSegments(depth - 2);
		return workspace.getResourceInfo(linkParent, false, false).isSet(ICoreConstants.M_LINK);
	}

	/* (non-Javadoc)
	 * @see IResource#setTeamPrivateMember(boolean)
	 */
	public void setTeamPrivateMember(boolean isTeamPrivate) throws CoreException {
		// fetch the info but don't bother making it mutable even though we are going
		// to modify it.  We don't know whether or not the tree is open and it really doesn't
		// matter as the change we are doing does not show up in deltas.
		ResourceInfo info = getResourceInfo(false, false);
		int flags = getFlags(info);
		checkAccessible(flags);
		// ignore attempts to set team private member flag on anything except files and folders
		if (info.getType() == FILE || info.getType() == FOLDER) {
			if (isTeamPrivate) {
				info.set(ICoreConstants.M_TEAM_PRIVATE_MEMBER);
			} else {
				info.clear(ICoreConstants.M_TEAM_PRIVATE_MEMBER);
			}
		}
	}

	/**
	 * Calls the move/delete hook to perform the deletion.  Since this method calls 
	 * client code, it is run "unprotected", so the workspace lock is not held.  
	 */
	private void unprotectedDelete(ResourceTree tree, int updateFlags, IProgressMonitor monitor) throws CoreException {
		IMoveDeleteHook hook = workspace.getMoveDeleteHook();
		switch (getType()) {
			case IResource.FILE :
				if (!hook.deleteFile(tree, (IFile) this, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000 / 2)))
					tree.standardDeleteFile((IFile) this, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000));
				break;
			case IResource.FOLDER :
				if (!hook.deleteFolder(tree, (IFolder) this, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000 / 2)))
					tree.standardDeleteFolder((IFolder) this, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000));
				break;
			case IResource.PROJECT :
				workspace.broadcastEvent(LifecycleEvent.newEvent(LifecycleEvent.PRE_PROJECT_DELETE, this));
				if (!hook.deleteProject(tree, (IProject) this, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000 / 2)))
					tree.standardDeleteProject((IProject) this, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000));
				break;
			case IResource.ROOT :
				IProject[] projects = ((IWorkspaceRoot) this).getProjects();
				for (int i = 0; i < projects.length; i++) {
					workspace.broadcastEvent(LifecycleEvent.newEvent(LifecycleEvent.PRE_PROJECT_DELETE, projects[i]));
					if (!hook.deleteProject(tree, projects[i], updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000 / projects.length / 2)))
						tree.standardDeleteProject(projects[i], updateFlags, Policy.subMonitorFor(monitor, Policy.opWork * 1000 / projects.length));
				}
		}
	}

	/**
	 * Calls the move/delete hook to perform the move.  Since this method calls 
	 * client code, it is run "unprotected", so the workspace lock is not held.  
	 * Returns true if resources were actually moved, and false otherwise.
	 */
	private boolean unprotectedMove(ResourceTree tree, final IResource destination, int updateFlags, IProgressMonitor monitor) throws CoreException, ResourceException {
		IMoveDeleteHook hook = workspace.getMoveDeleteHook();
		switch (getType()) {
			case IResource.FILE :
				if (isLinked())
					workspace.broadcastEvent(LifecycleEvent.newEvent(LifecycleEvent.PRE_LINK_MOVE, this, destination, updateFlags));
				if (!hook.moveFile(tree, (IFile) this, (IFile) destination, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork / 2)))
					tree.standardMoveFile((IFile) this, (IFile) destination, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork));
				break;
			case IResource.FOLDER :
				if (isLinked())
					workspace.broadcastEvent(LifecycleEvent.newEvent(LifecycleEvent.PRE_LINK_MOVE, this, destination, updateFlags));
				if (!hook.moveFolder(tree, (IFolder) this, (IFolder) destination, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork / 2)))
					tree.standardMoveFolder((IFolder) this, (IFolder) destination, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork));
				break;
			case IResource.PROJECT :
				IProject project = (IProject) this;
				// if there is no change in name, there is nothing to do so return.
				if (getName().equals(destination.getName()))
					return false;
				//we are deleting the source project so notify.
				workspace.broadcastEvent(LifecycleEvent.newEvent(LifecycleEvent.PRE_PROJECT_MOVE, this, destination, updateFlags));
				IProjectDescription description = project.getDescription();
				description.setName(destination.getName());
				if (!hook.moveProject(tree, project, description, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork / 2)))
					tree.standardMoveProject(project, description, updateFlags, Policy.subMonitorFor(monitor, Policy.opWork));
				break;
			case IResource.ROOT :
				String msg = Messages.resources_moveRoot;
				throw new ResourceException(new ResourceStatus(IResourceStatus.INVALID_VALUE, getFullPath(), msg));
		}
		return true;
	}
}