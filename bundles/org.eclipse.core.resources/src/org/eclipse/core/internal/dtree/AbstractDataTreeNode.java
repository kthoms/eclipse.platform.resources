/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.dtree;

import org.eclipse.core.internal.utils.Messages;
import org.eclipse.core.internal.utils.StringPool;
import org.eclipse.core.runtime.IPath;

/**
 * This class and its subclasses are used to represent nodes of AbstractDataTrees.
 * Refer to the DataTree API comments for more details.
 * @see AbstractDataTree
 */
public abstract class AbstractDataTreeNode {
	/**
	 * Singleton indicating no children.
	 */
	static final AbstractDataTreeNode[] NO_CHILDREN = new AbstractDataTreeNode[0];
	protected AbstractDataTreeNode children[];
	protected String name;

	/* Node types for comparison */
	public static final int T_COMPLETE_NODE = 0;
	public static final int T_DELTA_NODE = 1;
	public static final int T_DELETED_NODE = 2;
	public static final int T_NO_DATA_DELTA_NODE = 3;
	public static final int T_MISSING_NODE = 4;

	/**
	 * Creates a new data tree node
	 *
	 * @param name
	 *	name of new node
	 * @param children
	 *	children of the new node
	 */
	AbstractDataTreeNode(String name, AbstractDataTreeNode[] children) {
		this.name = name;
		if (children == null || children.length == 0)
			this.children = AbstractDataTreeNode.NO_CHILDREN;
		else
			this.children = children;
	}

	/**
	 * Returns a node which if applied to the receiver will produce 
	 * the corresponding node in the given parent tree.
	 *
	 * @param myTree  tree to which the node belongs
	 * @param parentTree  parent tree on which to base backward delta
	 * @param key  key of node in its tree
	 */
	abstract AbstractDataTreeNode asBackwardDelta(DeltaDataTree myTree, DeltaDataTree parentTree, IPath key);

	/**
	 * If this node is a node in a comparison tree, this method reverses
	 * the comparison for this node and all children
	 */
	AbstractDataTreeNode asReverseComparisonNode(IComparator comparator) {
		return this;
	}

	/**
	 * Returns the result of assembling nodes with the given forward delta nodes.
	 * Both arrays must be sorted by name.
	 * The result is sorted by name.
	 * If keepDeleted is true, explicit representations of deletions are kept,
	 * otherwise nodes to be deleted are removed in the result.
	 */
	static AbstractDataTreeNode[] assembleWith(AbstractDataTreeNode[] oldNodes, AbstractDataTreeNode[] newNodes, boolean keepDeleted) {

		// Optimize the common case where the new list is empty.
		if (newNodes.length == 0)
			return oldNodes;

		// Can't just return newNodes if oldNodes has length 0
		// because newNodes may contain deleted nodes.

		AbstractDataTreeNode[] resultNodes = new AbstractDataTreeNode[oldNodes.length + newNodes.length];

		// do a merge
		int oldIndex = 0;
		int newIndex = 0;
		int resultIndex = 0;
		while (oldIndex < oldNodes.length && newIndex < newNodes.length) {
			int compare = oldNodes[oldIndex].name.compareTo(newNodes[newIndex].name);
			if (compare == 0) {
				AbstractDataTreeNode node = oldNodes[oldIndex++].assembleWith(newNodes[newIndex++]);
				if (node != null && (!node.isDeleted() || keepDeleted)) {
					resultNodes[resultIndex++] = node;
				}
			} else if (compare < 0) {
				resultNodes[resultIndex++] = oldNodes[oldIndex++];
			} else if (compare > 0) {
				AbstractDataTreeNode node = newNodes[newIndex++];
				if (!node.isDeleted() || keepDeleted) {
					resultNodes[resultIndex++] = node;
				}
			}
		}
		while (oldIndex < oldNodes.length) {
			resultNodes[resultIndex++] = oldNodes[oldIndex++];
		}
		while (newIndex < newNodes.length) {
			AbstractDataTreeNode resultNode = newNodes[newIndex++];
			if (!resultNode.isDeleted() || keepDeleted) {
				resultNodes[resultIndex++] = resultNode;
			}
		}

		// trim size of result
		if (resultIndex < resultNodes.length) {
			System.arraycopy(resultNodes, 0, resultNodes = new AbstractDataTreeNode[resultIndex], 0, resultIndex);
		}
		return resultNodes;
	}

	/**
	 * Returns the result of assembling this node with the given forward delta node.
	 */
	AbstractDataTreeNode assembleWith(AbstractDataTreeNode node) {

		// If not a delta, or if the old node was deleted,
		// then the new node represents the complete picture.
		if (!node.isDelta() || this.isDeleted()) {
			return node;
		}

		// node must be either a DataDeltaNode or a NoDataDeltaNode
		if (node.hasData()) {
			if (this.isDelta()) {
				// keep deletions because they still need 
				// to hide child nodes in the parent.
				AbstractDataTreeNode[] assembledChildren = assembleWith(children, node.children, true);
				return new DataDeltaNode(name, node.getData(), assembledChildren);
			} else {
				// This is a complete picture, so deletions 
				// wipe out the child and are no longer useful
				AbstractDataTreeNode[] assembledChildren = assembleWith(children, node.children, false);
				return new DataTreeNode(name, node.getData(), assembledChildren);
			}
		} else {
			if (this.isDelta()) {
				AbstractDataTreeNode[] assembledChildren = assembleWith(children, node.children, true);
				if (this.hasData()) {
					return new DataDeltaNode(name, this.getData(), assembledChildren);
				} else {
					return new NoDataDeltaNode(name, assembledChildren);
				}
			} else {
				AbstractDataTreeNode[] assembledChildren = assembleWith(children, node.children, false);
				return new DataTreeNode(name, this.getData(), assembledChildren);
			}
		}
	}

	/**
	 * Returns the result of assembling this node with the given forward delta node.
	 */
	AbstractDataTreeNode assembleWith(AbstractDataTreeNode node, IPath key, int keyIndex) {

		// leaf case
		int keyLen = key.segmentCount();
		if (keyIndex == keyLen) {
			return assembleWith(node);
		}

		// non-leaf case
		int childIndex = indexOfChild(key.segment(keyIndex));
		if (childIndex >= 0) {
			AbstractDataTreeNode copy = copy();
			copy.children[childIndex] = children[childIndex].assembleWith(node, key, keyIndex + 1);
			return copy;
		}

		// Child not found.  Build up NoDataDeltaNode hierarchy for rest of key
		// and assemble with that.
		for (int i = keyLen - 2; i >= keyIndex; --i) {
			node = new NoDataDeltaNode(key.segment(i), node);
		}
		node = new NoDataDeltaNode(name, node);
		return assembleWith(node);
	}

	/**
	 * Returns the child with the given local name.  The child must exist.
	 */
	AbstractDataTreeNode childAt(String localName) {
		AbstractDataTreeNode node = childAtOrNull(localName);
		if (node != null) {
			return node;
		}
		throw new ObjectNotFoundException(Messages.bind(Messages.dtree_missingChild, localName));
	}

	/**
	 * Returns the child with the given local name.  Returns null if the child
	 * does not exist.
	 *
	 * @param localName
	 *	name of child to retrieve
	 */
	AbstractDataTreeNode childAtOrNull(String localName) {
		int index = indexOfChild(localName);
		return index >= 0 ? children[index] : null;
	}

	/**
	 * Returns the child with the given local name, ignoring case.  
	 * If multiple case variants exist, the search will favour real nodes
	 * over deleted nodes. If multiple real nodes are found, the first one
	 * encountered in case order is returned. Returns null if no matching
	 * children are found.
	 * 
	 * @param localName name of child to retrieve
	 */
	AbstractDataTreeNode childAtIgnoreCase(String localName) {
		AbstractDataTreeNode result = null;
		for (int i = 0; i < children.length; i++) {
			if (children[i].getName().equalsIgnoreCase(localName)) {
				//if we find a deleted child, keep looking for a real child
				if (children[i].isDeleted())
					result = children[i];
				else
					return children[i];
			}
		}
		return result;
	}

	/** 
	 */
	protected static AbstractDataTreeNode[] compareWith(AbstractDataTreeNode[] oldNodes, AbstractDataTreeNode[] newNodes, IComparator comparator) {

		int oldLen = oldNodes.length;
		int newLen = newNodes.length;
		int oldIndex = 0;
		int newIndex = 0;
		AbstractDataTreeNode[] comparedNodes = new AbstractDataTreeNode[oldLen + newLen];
		int count = 0;

		while (oldIndex < oldLen && newIndex < newLen) {
			DataTreeNode oldNode = (DataTreeNode) oldNodes[oldIndex];
			DataTreeNode newNode = (DataTreeNode) newNodes[newIndex];
			int compare = oldNode.name.compareTo(newNode.name);
			if (compare < 0) {
				/* give the client a chance to say whether it should be in the delta */
				int userComparison = comparator.compare(oldNode.getData(), null);
				if (userComparison != 0) {
					comparedNodes[count++] = convertToRemovedComparisonNode(oldNode, userComparison);
				}
				++oldIndex;
			} else if (compare > 0) {
				/* give the client a chance to say whether it should be in the delta */
				int userComparison = comparator.compare(null, newNode.getData());
				if (userComparison != 0) {
					comparedNodes[count++] = convertToAddedComparisonNode(newNode, userComparison);
				}
				++newIndex;
			} else {
				AbstractDataTreeNode comparedNode = oldNode.compareWith(newNode, comparator);
				NodeComparison comparison = (NodeComparison) comparedNode.getData();

				/* skip empty comparisions */
				if (!(comparison.isUnchanged() && comparedNode.size() == 0)) {
					comparedNodes[count++] = comparedNode;
				}
				++oldIndex;
				++newIndex;
			}
		}
		while (oldIndex < oldLen) {
			DataTreeNode oldNode = (DataTreeNode) oldNodes[oldIndex++];

			/* give the client a chance to say whether it should be in the delta */
			int userComparison = comparator.compare(oldNode.getData(), null);
			if (userComparison != 0) {
				comparedNodes[count++] = convertToRemovedComparisonNode(oldNode, userComparison);
			}
		}
		while (newIndex < newLen) {
			DataTreeNode newNode = (DataTreeNode) newNodes[newIndex++];

			/* give the client a chance to say whether it should be in the delta */
			int userComparison = comparator.compare(null, newNode.getData());
			if (userComparison != 0) {
				comparedNodes[count++] = convertToAddedComparisonNode(newNode, userComparison);
			}
		}

		if (count == 0) {
			return NO_CHILDREN;
		}
		if (count < comparedNodes.length) {
			System.arraycopy(comparedNodes, 0, comparedNodes = new AbstractDataTreeNode[count], 0, count);
		}
		return comparedNodes;
	}

	/** 
	 */
	protected static AbstractDataTreeNode[] compareWithParent(AbstractDataTreeNode[] nodes, IPath key, DeltaDataTree parent, IComparator comparator) {

		AbstractDataTreeNode[] comparedNodes = new AbstractDataTreeNode[nodes.length];
		int count = 0;
		for (int i = 0; i < nodes.length; ++i) {
			AbstractDataTreeNode node = nodes[i];
			AbstractDataTreeNode comparedNode = node.compareWithParent(key.append(node.getName()), parent, comparator);
			NodeComparison comparison = (NodeComparison) comparedNode.getData();
			// Skip it if it's an empty comparison (and no children).
			if (!(comparison.isUnchanged() && comparedNode.size() == 0)) {
				comparedNodes[count++] = comparedNode;
			}
		}
		if (count == 0) {
			return NO_CHILDREN;
		}
		if (count < comparedNodes.length) {
			System.arraycopy(comparedNodes, 0, comparedNodes = new AbstractDataTreeNode[count], 0, count);
		}
		return comparedNodes;
	}

	abstract AbstractDataTreeNode compareWithParent(IPath key, DeltaDataTree parent, IComparator comparator);

	static AbstractDataTreeNode convertToAddedComparisonNode(AbstractDataTreeNode newNode, int userComparison) {
		AbstractDataTreeNode[] children = newNode.getChildren();
		int n = children.length;
		AbstractDataTreeNode[] convertedChildren;
		if (n == 0) {
			convertedChildren = NO_CHILDREN;
		} else {
			convertedChildren = new AbstractDataTreeNode[n];
			for (int i = 0; i < n; ++i) {
				convertedChildren[i] = convertToAddedComparisonNode(children[i], userComparison);
			}
		}
		return new DataTreeNode(newNode.name, new NodeComparison(null, newNode.getData(), NodeComparison.K_ADDED, userComparison), convertedChildren);
	}

	static AbstractDataTreeNode convertToRemovedComparisonNode(AbstractDataTreeNode oldNode, int userComparison) {
		AbstractDataTreeNode[] children = oldNode.getChildren();
		int n = children.length;
		AbstractDataTreeNode[] convertedChildren;
		if (n == 0) {
			convertedChildren = NO_CHILDREN;
		} else {
			convertedChildren = new AbstractDataTreeNode[n];
			for (int i = 0; i < n; ++i) {
				convertedChildren[i] = convertToRemovedComparisonNode(children[i], userComparison);
			}
		}
		return new DataTreeNode(oldNode.name, new NodeComparison(oldNode.getData(), null, NodeComparison.K_REMOVED, userComparison), convertedChildren);
	}

	/**
	 * Returns a copy of the receiver which shares the receiver elements
	 */
	abstract AbstractDataTreeNode copy();

	/**
	 * Replaces the receiver's children between "from" and "to", with the children
	 * in otherNode starting at "start".  This method replaces the Smalltalk
	 * #replaceFrom:to:with:startingAt: method for copying children in data nodes
	 */
	protected void copyChildren(int from, int to, AbstractDataTreeNode otherNode, int start) {
		int other = start;
		for (int i = from; i <= to; i++, other++) {
			this.children[i] = otherNode.children[other];
		}
	}

	/**
	 * Returns an array of the node's children
	 */
	public AbstractDataTreeNode[] getChildren() {
		return children;
	}

	/**
	 * Returns the node's data
	 */
	Object getData() {
		throw new AbstractMethodError(Messages.dtree_subclassImplement);
	}

	/**
	 * return the name of the node
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns true if the receiver can carry data, false otherwise.
	 */
	boolean hasData() {
		return false;
	}

	/**
	 * Returns true if the receiver has a child with the given local name, 
	 * false otherwise
	 */
	boolean includesChild(String localName) {
		return indexOfChild(localName) != -1;
	}

	/**
	 * Returns the index of the specified child's name in the receiver.
	 */
	protected int indexOfChild(String localName) {
		AbstractDataTreeNode[] nodes = this.children;
		int left = 0;
		int right = nodes.length - 1;
		while (left <= right) {
			int mid = (left + right) / 2;
			int compare = localName.compareTo(nodes[mid].name);
			if (compare < 0) {
				right = mid - 1;
			} else if (compare > 0) {
				left = mid + 1;
			} else {
				return mid;
			}
		}
		return -1;
	}

	/**
	 * Returns true if the receiver represents a deleted node, false otherwise.
	 */
	boolean isDeleted() {
		return false;
	}

	/**
	 * Returns true if the receiver represents delta information,
	 * false if it represents the complete information.
	 */
	boolean isDelta() {
		return false;
	}

	/**
	 * Returns true if the receiver is an empty delta node, false otherwise.
	 */
	boolean isEmptyDelta() {
		return false;
	}

	/**
	 * Returns the local names of the receiver's children.
	 */
	String[] namesOfChildren() {
		String names[] = new String[children.length];
		/* copy child names (Reverse loop optimized) */
		for (int i = children.length; --i >= 0;)
			names[i] = children[i].getName();
		return names;
	}

	/**
	 * Sort the nodes in the given array.
	 */
	static void quickSort(AbstractDataTreeNode[] list, int left, int right) {

		int original_left = left;
		int original_right = right;

		AbstractDataTreeNode mid = list[(left + right) / 2];
		do {
			while (list[left].name.compareTo(mid.name) < 0) {
				left++;
			}
			while (mid.name.compareTo(list[right].name) < 0) {
				right--;
			}
			if (left <= right) {
				AbstractDataTreeNode tmp = list[left];
				list[left] = list[right];
				list[right] = tmp;
				left++;
				right--;
			}
		} while (left <= right);

		if (original_left < right) {
			quickSort(list, original_left, right);
		}
		if (left < original_right) {
			quickSort(list, left, original_right);
		}
	}

	/**
	 * Replaces the child with the given local name.
	 */
	void replaceChild(String localName, DataTreeNode node) {
		int i = indexOfChild(localName);
		if (i >= 0) {
			children[i] = node;
		} else {
			throw new ObjectNotFoundException(Messages.bind(Messages.dtree_missingChild, localName));
		}
	}

	/**
	 * Set the node's children
	 */
	protected void setChildren(AbstractDataTreeNode newChildren[]) {
		children = newChildren;
	}

	/**
	 * Set the node's name
	 */
	void setName(String s) {
		name = s;
	}

	/** 
	 * Simplifies the given nodes, and answers their replacements.
	 */
	protected static AbstractDataTreeNode[] simplifyWithParent(AbstractDataTreeNode[] nodes, IPath key, DeltaDataTree parent, IComparator comparer) {

		AbstractDataTreeNode[] simplifiedNodes = new AbstractDataTreeNode[nodes.length];
		int simplifiedCount = 0;
		for (int i = 0; i < nodes.length; ++i) {
			AbstractDataTreeNode node = nodes[i];
			AbstractDataTreeNode simplifiedNode = node.simplifyWithParent(key.append(node.getName()), parent, comparer);
			if (!simplifiedNode.isEmptyDelta()) {
				simplifiedNodes[simplifiedCount++] = simplifiedNode;
			}
		}
		if (simplifiedCount == 0) {
			return NO_CHILDREN;
		}
		if (simplifiedCount < simplifiedNodes.length) {
			System.arraycopy(simplifiedNodes, 0, simplifiedNodes = new AbstractDataTreeNode[simplifiedCount], 0, simplifiedCount);
		}
		return simplifiedNodes;
	}

	/** 
	 * Simplifies the given node, and answers its replacement.
	 */
	abstract AbstractDataTreeNode simplifyWithParent(IPath key, DeltaDataTree parent, IComparator comparer);

	/**
	 * Returns the number of children of the receiver
	 */
	int size() {
		return children.length;
	}

	/**
	 * Sort the nodes in the given array.
	 */
	public static void sort(AbstractDataTreeNode[] list) {
		if (list.length > 1) {
			quickSort(list, 0, list.length - 1);
		}
	}

	/* (non-Javadoc
	 * Method declared on IStringPoolParticipant
	 */
	public void storeStrings(StringPool set) {
		name = set.add(name);
		//copy children pointer in case of concurrent modification
		AbstractDataTreeNode[] nodes = children;
		if (nodes != null)
			for (int i = nodes.length; --i >= 0;)
				nodes[i].storeStrings(set);
	}

	/**
	 * Returns a unicode representation of the node.  This method is used
	 * for debugging purposes only (no NLS support needed)
	 */
	public String toString() {
		return "an AbstractDataTreeNode(" + this.getName() + ") with " + getChildren().length + " children."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * Returns a constant describing the type of node.
	 */
	abstract int type();
}